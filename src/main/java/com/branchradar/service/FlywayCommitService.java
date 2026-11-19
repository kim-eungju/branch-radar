package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.FlywayMigration;
import com.branchradar.dto.FlywayVersion;
import com.branchradar.service.FlywayBoard.Action;
import com.branchradar.service.FlywayBoard.Archive;
import com.branchradar.service.FlywayBoard.LogEntry;
import com.branchradar.service.FlywayBoard.Post;
import com.branchradar.service.FlywayLocalStore.LegacyDraft;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flyway 관리: 이번 묶음(작업자별 조각)과, 남은 조각을 합친 Flyway 파일 하나를 여러 브랜치에 추가하는 커밋.
 * 이 도구의 유일한 쓰기 기능이다.
 * <p>
 * - 조각은 누구나 등록·수정·삭제·복구할 수 있고, 모두 변경 이력에 남는다. 삭제한 조각도 내용은 남는다.
 * - 조각을 고칠 때는 조각의 revision, 커밋할 때는 묶음의 revision 을 확인해서
 *   다른 사람이 먼저 바꾼 내용을 모르고 덮어쓰거나 빼고 커밋하는 일을 막는다.
 * - 커밋 파일은 조각마다 등록자·설명 주석을 붙여 순서대로 합친다.
 * - 일부 브랜치에만 커밋되면 버전과 파일 내용을 고정하고 조각 수정을 막는다 (남은 브랜치에 같은 파일을 올리기 위해).
 * - 모든 브랜치에 커밋되거나 마감하면 묶음을 history 폴더에 보관하고 비운다.
 * <p>
 * 파일명은 직접 정하지 않는다: V{오늘 yyMMdd}.{전체 브랜치 기준 다음 순번 3자리}__{설정값}.sql
 */
@Service
public class FlywayCommitService {

    /** 커밋 제목 앞머리: 새 이모지 + 공백 한 칸 */
    static final String SUMMARY_PREFIX = new String(Character.toChars(0x1F426)) + " ";

    static final int MAX_SQL_BYTES = 1024 * 1024;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_LISTED_BRANCHES = 5;
    private static final String RULE = "-- " + "=".repeat(60);
    private static final DateTimeFormatter VERSION_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final GitLabProperties properties;
    private final GitLabProperties.Flyway cfg;
    private final GitLabClient client;
    private final FlywayService flywayService;
    private final BranchService branchService;
    private final FlywayLocalStore store;
    private final Clock clock;

    public FlywayCommitService(GitLabProperties properties, GitLabClient client, FlywayService flywayService,
                               BranchService branchService, FlywayLocalStore store, Clock clock) {
        this.properties = properties;
        this.cfg = properties.flyway();
        this.client = client;
        this.flywayService = flywayService;
        this.branchService = branchService;
        this.store = store;
        this.clock = clock;
    }

    /** 작업자 목록 (application.yml 의 branch-radar.flyway.workers) */
    public List<String> workers() {
        return cfg.workers();
    }

    // ================================================================ 입출력

    /**
     * 조각 등록·수정.
     *
     * @param actor    지금 화면을 쓰는 사람 (작업자 목록 중 한 명)
     * @param revision 수정할 때 화면이 본 조각의 revision
     */
    public record PostRequest(String actor, String description, String sql, Long revision) {
    }

    /**
     * 검증·커밋·마감.
     *
     * @param revision 화면이 본 묶음의 revision
     */
    public record CommitRequest(String actor, List<String> branches, Long revision) {
    }

    /** 브랜치 하나에 대한 검증 결과 */
    public record BranchPlan(String branch, String headSha, boolean protectedBranch,
                             List<String> errors, List<String> warnings) {
    }

    /**
     * 검증 결과. ok 여야 커밋할 수 있다.
     *
     * @param content  커밋할 파일 내용 (조각을 합친 것)
     * @param errors   브랜치와 무관한 오류 (작업자 미선택, 조각 없음 등)
     * @param warnings 커밋은 가능하지만 확인이 필요한 사항
     * @param notes    참고 정보
     */
    public record Plan(String fileName, String filePath, String commitMessage, String actor,
                       int posts, int sqlBytes, int sqlLines, String content,
                       List<String> errors, List<String> warnings, List<String> notes,
                       List<BranchPlan> branches, boolean ok) {
    }

    public record BranchResult(String branch, boolean success, String sha, String shortSha, String webUrl, String error) {
    }

    /**
     * @param allSucceeded 선택한 모든 브랜치에 커밋되었는지. false 면 실패한 브랜치가 묶음에 남는다
     * @param localFile    로컬 폴더에 저장한 파일 경로 (저장하지 못했으면 null, 사유는 localError)
     * @param archive      묶음을 보관했으면 보관 이름
     */
    public record Committed(String fileName, String filePath, List<BranchResult> results, boolean allSucceeded,
                            String localFile, String localError, String archive) {
    }

    /** 검증 오류가 있어 커밋하지 않았을 때 */
    public static class InvalidDraftException extends RuntimeException {
        private final transient Plan plan;

        public InvalidDraftException(Plan plan) {
            super("검증 오류가 있어 커밋하지 않았습니다.");
            this.plan = plan;
        }

        public Plan getPlan() {
            return plan;
        }
    }

    /** 화면이 보고 있던 조각·묶음이 최신이 아닐 때 (다른 사람이 먼저 바꿨거나 이미 커밋됨) */
    public static class DraftConflictException extends RuntimeException {
        public DraftConflictException(String message) {
            super(message);
        }
    }

    private record Prepared(Plan plan, String content, String version) {
    }

    // ================================================================ 묶음 조회

    /** 현재 묶음. 없으면 빈 묶음 (이전 임시저장이 남아 있으면 조각 하나로 옮긴다). */
    public synchronized FlywayBoard board() {
        FlywayBoard board = store.readBoard();
        if (board != null) return board;
        board = new FlywayBoard();
        LegacyDraft legacy = store.readLegacyDraft();
        if (legacy != null) {
            migrate(legacy, board);
            store.writeBoard(board);
            store.deleteLegacyDraft();
        }
        return board;
    }

    /** 예전 임시저장을 조각 하나로 옮긴다. 일부 커밋 상태였다면 그 내용 그대로 고정한다. */
    private void migrate(LegacyDraft d, FlywayBoard board) {
        String sql = d.sql() == null ? "" : d.sql();
        String who = workers().contains(text(d.worker())) ? text(d.worker()) : "(이전 임시저장)";
        if (!sql.isBlank()) {
            Post p = new Post();
            p.id = board.nextId++;
            p.author = who;
            p.description = text(d.description()).isEmpty() ? "이전 임시저장에서 옮김" : text(d.description());
            p.sql = sql;
            p.createdAt = d.updatedAt() != null ? d.updatedAt() : stamp();
            p.updatedAt = p.createdAt;
            p.updatedBy = who;
            p.revision = 1;
            board.posts.add(p);
        }
        if (d.pinnedVersion() != null) {
            // 이미 일부 브랜치에 올라간 파일과 같은 내용이어야 하므로 예전 방식(SQL 그대로) 그대로 고정한다
            board.pinnedVersion = d.pinnedVersion();
            board.pinnedContent = normalize(sql);
            board.pendingBranches = d.branches() == null ? new ArrayList<>() : new ArrayList<>(d.branches());
        }
        if (!board.posts.isEmpty() || board.isLocked()) {
            log(board, who, Action.MIGRATE, board.posts.isEmpty() ? 0 : board.posts.get(0).id,
                    "예전 임시저장을 옮김", null, null);
            board.revision = nextRevision(0);
        }
    }

    // ================================================================ 조각 등록 · 수정 · 삭제 · 복구 · 순서

    public synchronized FlywayBoard createPost(PostRequest r) {
        FlywayBoard board = board();
        String actor = requireActor(r.actor());
        requireUnlocked(board);
        String description = requireDescription(r.description());
        String sql = requireSql(r.sql());

        Post p = new Post();
        p.id = board.nextId++;
        p.author = actor;
        p.description = description;
        p.sql = sql;
        p.createdAt = stamp();
        p.updatedAt = p.createdAt;
        p.updatedBy = actor;
        p.revision = 1;
        board.posts.add(p);
        log(board, actor, Action.CREATE, p.id, summary(p), null, null);
        return save(board);
    }

    public synchronized FlywayBoard updatePost(long id, PostRequest r) {
        FlywayBoard board = board();
        String actor = requireActor(r.actor());
        requireUnlocked(board);
        Post p = board.find(id);
        checkPostRevision(p, r.revision());
        if (p.deleted) throw new IllegalArgumentException("삭제된 조각은 수정할 수 없습니다. 먼저 복구하세요.");
        String description = requireDescription(r.description());
        String sql = requireSql(r.sql());
        if (description.equals(p.description) && sql.equals(p.sql)) return board;   // 바뀐 것 없음

        String changed = description.equals(p.description) ? "SQL 수정"
                : sql.equals(p.sql) ? "설명 수정" : "설명·SQL 수정";
        log(board, actor, Action.UPDATE, p.id, changed + " (작성 " + p.author + ")", p.description, p.sql);
        p.description = description;
        p.sql = sql;
        p.updatedAt = stamp();
        p.updatedBy = actor;
        p.revision++;
        return save(board);
    }

    public synchronized FlywayBoard deletePost(long id, PostRequest r) {
        FlywayBoard board = board();
        String actor = requireActor(r.actor());
        requireUnlocked(board);
        Post p = board.find(id);
        checkPostRevision(p, r.revision());
        if (p.deleted) return board;
        p.deleted = true;
        p.deletedAt = stamp();
        p.deletedBy = actor;
        p.revision++;
        log(board, actor, Action.DELETE, p.id, summary(p) + " (작성 " + p.author + ")", p.description, p.sql);
        return save(board);
    }

    public synchronized FlywayBoard restorePost(long id, PostRequest r) {
        FlywayBoard board = board();
        String actor = requireActor(r.actor());
        requireUnlocked(board);
        Post p = board.find(id);
        if (!p.deleted) return board;
        p.deleted = false;
        p.deletedAt = null;
        p.deletedBy = null;
        p.revision++;
        // 복구한 조각은 맨 뒤로 보낸다 (그 사이 다른 조각이 추가되었을 수 있으므로 순서를 다시 확인하게)
        board.posts.remove(p);
        board.posts.add(p);
        log(board, actor, Action.RESTORE, p.id, summary(p) + " (작성 " + p.author + ") · 맨 아래로", null, null);
        return save(board);
    }

    /**
     * 남아 있는 조각 사이에서 한 칸 위/아래로 옮긴다. 순서 = 파일 안의 실행 순서.
     *
     * @param up true 면 위로
     */
    public synchronized FlywayBoard movePost(long id, boolean up, PostRequest r) {
        FlywayBoard board = board();
        String actor = requireActor(r.actor());
        requireUnlocked(board);
        Post p = board.find(id);
        if (p.deleted) throw new IllegalArgumentException("삭제된 조각은 순서를 바꿀 수 없습니다.");
        List<Post> active = new ArrayList<>(board.getActive());
        int i = active.indexOf(p);
        int j = up ? i - 1 : i + 1;
        if (j < 0 || j >= active.size()) return board;
        Post other = active.get(j);
        // 전체 목록(삭제된 조각 포함)에서 두 조각의 자리를 바꾼다
        int a = board.posts.indexOf(p);
        int b = board.posts.indexOf(other);
        board.posts.set(a, other);
        board.posts.set(b, p);
        log(board, actor, Action.MOVE, p.id, "#" + p.id + " 을(를) #" + other.id + " " + (up ? "위로" : "아래로")
                + " (" + (i + 1) + "번째 → " + (j + 1) + "번째)", null, null);
        return save(board);
    }

    // ================================================================ 파일 내용 · 파일명

    /** 지금 커밋하면 만들어질 파일 내용. 일부 커밋으로 고정되어 있으면 그 내용 그대로. */
    public String content(FlywayBoard board) {
        return board.pinnedContent != null ? board.pinnedContent : buildContent(board.getActive());
    }

    /**
     * 조각마다 등록자·설명 주석을 붙여 순서대로 합친다.
     * <pre>
     * -- ============================================================
     * -- [1/2] 등록자 : ejkim | 등록 : 2026-11-19 09:12
     * -- 설명 : 주문 테이블 컬럼 추가
     * -- ============================================================
     * ALTER TABLE ...;
     * </pre>
     */
    static String buildContent(List<Post> posts) {
        StringBuilder sb = new StringBuilder();
        int n = posts.size();
        for (int i = 0; i < n; i++) {
            Post p = posts.get(i);
            if (i > 0) sb.append('\n');
            sb.append(RULE).append('\n');
            sb.append("-- [").append(i + 1).append('/').append(n).append("] 등록자 : ").append(oneLine(p.author))
                    .append(" | 등록 : ").append(p.createdAt);
            if (p.isEdited()) {
                sb.append(" | 수정 : ").append(oneLine(p.updatedBy)).append(' ').append(p.updatedAt);
            }
            sb.append('\n');
            List<String> lines = (p.description == null ? "" : p.description).replace("\r\n", "\n").replace('\r', '\n')
                    .lines().map(String::stripTrailing).toList();
            for (int k = 0; k < Math.max(1, lines.size()); k++) {
                String line = (k == 0 ? "-- 설명 : " : "--        ") + (lines.isEmpty() ? "" : lines.get(k));
                sb.append(line.stripTrailing()).append('\n');
            }
            sb.append(RULE).append('\n');
            sb.append(normalize(p.sql));
        }
        return sb.toString();
    }

    /** 줄바꿈은 LF 로 통일하고, 끝의 공백 줄은 없애고 줄바꿈 하나로 끝낸다. */
    static String normalize(String sql) {
        String s = sql == null ? "" : sql.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        return s.isEmpty() ? "" : s + "\n";
    }

    /**
     * 지금 커밋하면 쓰게 될 파일명. 일부 브랜치에만 커밋된 상태면 그때의 버전을 그대로 쓴다.
     *
     * @param all 전체 브랜치의 버전
     */
    public String expectedFileName(Collection<FlywayVersion> all) {
        FlywayBoard board = board();
        String version = board.pinnedVersion != null ? board.pinnedVersion : nextVersion(all, now().toLocalDate());
        return fileName(version);
    }

    public static List<FlywayVersion> versions(List<BranchSnapshot> snapshots) {
        List<FlywayVersion> all = new ArrayList<>();
        for (BranchSnapshot s : snapshots) {
            s.versioned().forEach(m -> all.add(m.version()));
        }
        return all;
    }

    private String fileName(String version) {
        String suffix = cfg.suffixes().isEmpty() ? ".sql" : cfg.suffixes().get(0);
        return cfg.versionedPrefix() + version + cfg.separator() + cfg.newFileDescription() + suffix;
    }

    /**
     * 오늘 날짜(yyMMdd) + 다음 순번(3자리). 순번은 전체 브랜치에서 오늘 날짜로 쓰인 최대 순번 + 1 이다.
     * 예) 오늘 261112, 어느 브랜치에 261112.002 가 있으면 → 261112.003
     */
    static String nextVersion(Collection<FlywayVersion> all, LocalDate today) {
        String head = today.format(VERSION_DATE);
        BigInteger headNumber = new BigInteger(head);
        BigInteger max = BigInteger.ZERO;
        for (FlywayVersion v : all) {
            String[] tokens = v.getRaw().split("[._]");
            if (!new BigInteger(tokens[0]).equals(headNumber)) continue;
            BigInteger seq = tokens.length > 1 ? new BigInteger(tokens[1]) : BigInteger.ZERO;
            if (seq.compareTo(max) > 0) max = seq;
        }
        String seq = max.add(BigInteger.ONE).toString();
        return head + "." + "0".repeat(Math.max(0, 3 - seq.length())) + seq;
    }

    // ================================================================ 검증 · 커밋 · 마감

    /** 커밋하지 않고 검증만 한다. 브랜치 목록 조회 실패 시 GitLabException. */
    public Plan plan(CommitRequest request) {
        return prepare(request, board()).plan();
    }

    /**
     * 검증 후 선택한 브랜치마다 같은 파일을 추가하는 커밋을 만든다.
     * 하나라도 검증 오류가 있으면 어느 브랜치에도 커밋하지 않는다.
     * 커밋 도중 일부 브랜치만 GitLab 이 거부하면, 성공한 브랜치는 그대로 두고 버전과 내용을 고정해 실패한 브랜치만 남긴다.
     * 모두 커밋되면 묶음을 보관하고 비운다.
     *
     * @throws DraftConflictException 다른 사람이 먼저 바꾼 내용이 있을 때 (그 내용을 모르고 커밋하지 않도록)
     * @throws InvalidDraftException  검증 오류가 있을 때
     */
    public synchronized Committed commit(CommitRequest request) {
        FlywayBoard board = board();
        checkBoardRevision(board, request.revision());

        Prepared p = prepare(request, board);
        Plan plan = p.plan();
        if (!plan.ok()) {
            throw new InvalidDraftException(plan);
        }

        List<BranchResult> results = new ArrayList<>();
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (BranchPlan bp : plan.branches()) {
            try {
                GitLabClient.Commit c = client.createFileCommit(bp.branch(), plan.filePath(), p.content(),
                        plan.commitMessage(), null, null);
                String sha = c == null ? null : c.id();
                String shortSha = c == null ? null
                        : c.shortId() != null ? c.shortId()
                        : sha == null ? null : sha.substring(0, Math.min(8, sha.length()));
                succeeded.add(bp.branch());
                results.add(new BranchResult(bp.branch(), true, sha, shortSha, c == null ? null : c.webUrl(), null));
            } catch (GitLabException e) {
                failed.add(bp.branch());
                results.add(new BranchResult(bp.branch(), false, null, null, null, e.getMessage()));
            }
        }

        if (succeeded.isEmpty()) {
            return new Committed(plan.fileName(), plan.filePath(), results, false, null, null, null);
        }

        String localFile = null;
        String localError = null;
        String relative = flywayService.relativePath(plan.filePath());
        try {
            store.write(relative, p.content().getBytes(StandardCharsets.UTF_8), true);
            localFile = store.resolve(relative).toString();
        } catch (Exception e) {
            localError = "커밋은 되었지만 로컬 폴더에 저장하지 못했습니다: " + e.getMessage();
        }

        String actor = plan.actor();
        board.committedBranches.addAll(succeeded);
        String archive = null;
        if (failed.isEmpty()) {
            log(board, actor, Action.COMMIT, 0, plan.fileName() + " → " + String.join(", ", succeeded), null, null);
            board.pinnedContent = p.content();
            archive = archive(board, plan.fileName(), plan.filePath(), actor, List.of());
        } else {
            board.pinnedVersion = p.version();
            board.pinnedContent = p.content();
            board.pendingBranches = new ArrayList<>(failed);
            log(board, actor, Action.PARTIAL_COMMIT, 0, plan.fileName() + " · 성공 " + String.join(", ", succeeded)
                    + " · 실패 " + String.join(", ", failed), null, null);
            save(board);
        }
        return new Committed(plan.fileName(), plan.filePath(), results, failed.isEmpty(), localFile, localError, archive);
    }

    /**
     * 일부 브랜치에만 커밋된 묶음을, 남은 브랜치는 커밋하지 않고 끝낸다. 묶음을 보관하고 비운다.
     *
     * @return 보관 이름
     */
    public synchronized String close(CommitRequest request) {
        FlywayBoard board = board();
        String actor = requireActor(request.actor());
        checkBoardRevision(board, request.revision());
        if (!board.isLocked()) {
            throw new IllegalArgumentException("일부 브랜치에만 커밋된 묶음만 마감할 수 있습니다.");
        }
        List<String> abandoned = List.copyOf(board.pendingBranches);
        String fileName = fileName(board.pinnedVersion);
        log(board, actor, Action.CLOSE, 0, fileName + " · 커밋하지 않은 브랜치: "
                + (abandoned.isEmpty() ? "-" : String.join(", ", abandoned)), null, null);
        return archive(board, fileName, null, actor, abandoned);
    }

    private String archive(FlywayBoard board, String fileName, String filePath, String actor, List<String> abandoned) {
        Archive a = new Archive();
        a.fileName = fileName;
        a.filePath = filePath;
        a.closedAt = stamp();
        a.closedBy = actor;
        a.committedBranches = new ArrayList<>(new LinkedHashSet<>(board.committedBranches));
        a.abandonedBranches = new ArrayList<>(abandoned);
        a.content = content(board);
        a.board = board;
        String name = store.writeArchive(a);
        store.deleteBoard();
        return name;
    }

    public List<String> archives() {
        return store.listArchives();
    }

    /** @return 없으면 null */
    public Archive archive(String name) {
        return store.readArchive(name);
    }

    private Prepared prepare(CommitRequest r, FlywayBoard board) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!properties.gitlab().hasToken()) {
            errors.add("GitLab 토큰이 설정되지 않아 커밋할 수 없습니다 (application.yml 의 branch-radar.gitlab.token).");
        }
        String actor = text(r.actor());
        if (workers().isEmpty()) {
            errors.add("작업자 목록이 비어 있습니다 (application.yml 의 branch-radar.flyway.workers).");
        } else if (!workers().contains(actor)) {
            errors.add("작업자(나)를 선택하세요.");
        }

        List<Post> posts = board.getActive();
        String content = content(board);
        if (content.isBlank()) {
            errors.add("커밋할 조각이 없습니다.");
        }
        if (!board.isLocked()) {
            for (Post p : posts) {
                if (!p.isTerminated()) {
                    warnings.add("#" + p.id + " (" + p.author + ") 의 SQL 이 ; 로 끝나지 않습니다. "
                            + "다음 조각의 SQL 과 붙어 실행될 수 있습니다.");
                }
            }
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SQL_BYTES) {
            errors.add("SQL 이 너무 큽니다 (" + bytes.length / 1024 + "KB, 최대 " + MAX_SQL_BYTES / 1024 + "KB).");
        }
        int lines = content.isEmpty() ? 0 : (int) content.chars().filter(ch -> ch == '\n').count();

        List<String> targets = cleanBranches(r.branches());
        if (targets.isEmpty()) {
            errors.add("커밋할 브랜치를 하나 이상 선택하세요.");
        }
        if (board.isLocked()) {
            List<String> again = targets.stream().filter(board.committedBranches::contains).toList();
            if (!again.isEmpty()) {
                errors.add("이미 이 파일을 커밋한 브랜치입니다: " + String.join(", ", again));
            }
        }

        // 브랜치 목록을 지금 다시 조회해서 최신 커밋 기준으로 판단한다
        List<GitLabClient.Branch> branches = client.listBranches().items();
        List<BranchSnapshot> snapshots = branchService.snapshots(branches);
        Map<String, GitLabClient.Branch> branchByName = new HashMap<>();
        branches.forEach(b -> branchByName.put(b.name(), b));
        Map<String, BranchSnapshot> snapshotByName = new HashMap<>();
        snapshots.forEach(s -> snapshotByName.put(s.name(), s));

        String version = board.pinnedVersion != null
                ? board.pinnedVersion : nextVersion(versions(snapshots), now().toLocalDate());
        String fileName = fileName(version);
        String filePath = joinPath(cfg.normalizedPath(), baseDirectory(snapshotByName.get(properties.branch().base())), fileName);
        String message = commitMessage(fileName, actor, posts);

        FlywayMigration candidate = null;
        try {
            candidate = flywayService.parse(filePath, blobSha(bytes));
        } catch (IllegalArgumentException e) {
            // 아래에서 오류로 처리
        }
        if (candidate == null || candidate.isRepeatable()) {
            candidate = null;
            errors.add("만들어진 파일명 '" + fileName + "' 이 Flyway 이름 규칙으로 해석되지 않습니다. "
                    + "branch-radar.flyway 설정(접두어, 구분자, new-file-description)을 확인하세요.");
        }

        List<BranchPlan> plans = new ArrayList<>();
        for (String name : targets) {
            List<String> be = new ArrayList<>();
            List<String> bw = new ArrayList<>();
            GitLabClient.Branch gb = branchByName.get(name);
            BranchSnapshot snap = snapshotByName.get(name);
            if (gb == null || snap == null) {
                be.add("브랜치를 찾을 수 없습니다.");
            } else if (!snap.ok()) {
                be.add("이 브랜치의 Flyway 목록을 조회하지 못했습니다: " + snap.error());
            } else {
                if (gb.isProtected()) {
                    bw.add("보호 브랜치입니다. 토큰 계정이 push 허용 대상이 아니면 GitLab 이 커밋을 거부합니다.");
                }
                if (candidate != null) checkTarget(candidate, snap, be, bw);
            }
            plans.add(new BranchPlan(name, gb == null ? null : gb.commit().id(), gb != null && gb.isProtected(),
                    List.copyOf(be), List.copyOf(bw)));
        }

        if (candidate != null) {
            checkOtherBranches(candidate, targets, snapshots, warnings, notes);
        }

        boolean ok = errors.isEmpty() && plans.stream().allMatch(bp -> bp.errors().isEmpty());
        Plan plan = new Plan(fileName, filePath, message, actor, posts.size(), bytes.length, lines, content,
                List.copyOf(errors), List.copyOf(warnings), List.copyOf(notes), List.copyOf(plans), ok);
        return new Prepared(plan, content, version);
    }

    /**
     * 커밋 메시지.
     * <pre>
     * 🐦 V261119.001__VADA.sql
     *
     * 등록자 : ejkim, ddd
     * 커밋 : ejkim
     * 설명 :
     * - 주문 테이블 컬럼 추가 (ejkim)
     * - 코드 데이터 추가 (ddd)
     * </pre>
     * 조각이 하나면 "설명 : ..." 한 줄이다.
     */
    static String commitMessage(String fileName, String actor, List<Post> posts) {
        LinkedHashSet<String> authors = new LinkedHashSet<>();
        posts.forEach(p -> authors.add(oneLine(p.author)));
        StringBuilder sb = new StringBuilder(SUMMARY_PREFIX).append(fileName).append("\n\n")
                .append("등록자 : ").append(String.join(", ", authors)).append('\n')
                .append("커밋 : ").append(actor).append('\n');
        if (posts.size() == 1) {
            sb.append("설명 : ").append(firstLine(posts.get(0).description));
        } else {
            sb.append("설명 :");
            for (Post p : posts) {
                sb.append("\n- ").append(firstLine(p.description)).append(" (").append(oneLine(p.author)).append(')');
            }
        }
        return sb.toString().stripTrailing();
    }

    // ================================================================ 공통

    private FlywayBoard save(FlywayBoard board) {
        board.revision = nextRevision(board.revision);
        store.writeBoard(board);
        return board;
    }

    private void log(FlywayBoard board, String who, Action action, long postId, String detail,
                     String beforeDescription, String beforeSql) {
        LogEntry e = new LogEntry();
        e.at = stamp();
        e.who = who;
        e.action = action;
        e.postId = postId;
        e.detail = detail;
        e.beforeDescription = beforeDescription;
        e.beforeSql = beforeSql;
        board.log.add(e);
    }

    private String requireActor(String actor) {
        String a = text(actor);
        if (!workers().contains(a)) {
            throw new IllegalArgumentException("작업자(나)를 먼저 선택하세요.");
        }
        return a;
    }

    private static void requireUnlocked(FlywayBoard board) {
        if (board.isLocked()) {
            throw new IllegalArgumentException("일부 브랜치에 이미 커밋된 묶음이라 조각을 바꿀 수 없습니다. "
                    + "남은 브랜치에 커밋하거나 마감한 뒤 새 조각을 등록하세요.");
        }
    }

    private static String requireDescription(String description) {
        String d = text(description);
        if (d.isEmpty()) throw new IllegalArgumentException("설명을 입력하세요. 커밋 파일에 주석으로 들어갑니다.");
        if (d.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("설명이 너무 깁니다 (최대 " + MAX_DESCRIPTION + "자).");
        }
        return d;
    }

    private static String requireSql(String sql) {
        String s = sql == null ? "" : sql.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        if (s.isBlank()) throw new IllegalArgumentException("SQL 을 입력하세요.");
        if (s.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw new IllegalArgumentException("SQL 이 너무 큽니다 (최대 " + MAX_SQL_BYTES / 1024 + "KB).");
        }
        return s;
    }

    private static void checkPostRevision(Post p, Long revision) {
        long got = revision == null ? 0 : revision;
        if (got == p.revision) return;
        String who = p.deleted ? p.deletedBy : p.updatedBy;
        String when = p.deleted ? p.deletedAt : p.updatedAt;
        throw new DraftConflictException("#" + p.id + " 은(는) " + who + " 이(가) " + when + " 에 먼저 "
                + (p.deleted ? "삭제" : "수정") + "했습니다. 지금 내용을 따로 복사해 두고 새로고침하세요.");
    }

    private static void checkBoardRevision(FlywayBoard board, Long revision) {
        long got = revision == null ? 0 : revision;
        if (got == board.revision) return;
        LogEntry last = board.getLastLog();
        String detail = last == null ? "이미 커밋되었거나 마감되었습니다."
                : last.who + " 이(가) " + last.at + " 에 " + last.action.getLabel() + "했습니다.";
        throw new DraftConflictException("보고 있던 묶음이 최신이 아닙니다 — " + detail
                + " 새로고침해서 바뀐 조각을 확인한 뒤 다시 커밋하세요.");
    }

    /** 항상 커지는 값. 묶음이 지워졌다 다시 생겨도 예전 화면의 revision 과 겹치지 않게 시각을 쓴다. */
    private long nextRevision(long current) {
        return Math.max(clock.millis(), current + 1);
    }

    private String stamp() {
        return now().format(STAMP);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(properties.staleBranch().zoneId()));
    }

    private static String summary(Post p) {
        return "#" + p.id + " " + firstLine(p.description);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.strip();
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).strip();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static List<String> cleanBranches(List<String> branches) {
        Set<String> set = new LinkedHashSet<>();
        if (branches != null) {
            for (String b : branches) {
                if (b != null && !b.isBlank()) set.add(b.strip());
            }
        }
        return List.copyOf(set);
    }

    /** 새 파일을 둘 하위 폴더: 기준 브랜치의 최신 마이그레이션이 있는 폴더 */
    private String baseDirectory(BranchSnapshot base) {
        if (base == null || !base.ok() || base.latest() == null) return "";
        String relative = flywayService.relativePath(base.latest().path());
        int idx = relative.lastIndexOf('/');
        return idx < 0 ? "" : relative.substring(0, idx);
    }

    private void checkTarget(FlywayMigration candidate, BranchSnapshot snap, List<String> errors, List<String> warnings) {
        List<FlywayMigration> existing = new ArrayList<>(snap.versioned());
        existing.addAll(snap.repeatable());
        if (existing.stream().anyMatch(m -> m.path().equalsIgnoreCase(candidate.path()))) {
            errors.add("같은 경로의 파일이 이미 있습니다: " + candidate.path());
            return;
        }
        snap.versioned().stream()
                .filter(m -> m.version().equals(candidate.version()))
                .findFirst()
                .ifPresent(m -> errors.add("같은 버전이 이미 있습니다: " + m.fileName()
                        + " — 한 브랜치에 같은 버전이 둘이면 Flyway 실행 시 오류가 납니다."));
        if (snap.dirMissing()) {
            warnings.add("마이그레이션 디렉터리(" + cfg.normalizedPath() + ")가 없습니다. 커밋하면 새로 만들어집니다.");
        }
        FlywayMigration latest = snap.latest();
        if (latest != null && candidate.version().compareTo(latest.version()) < 0) {
            warnings.add("이 브랜치의 최신 버전(" + latest.version().getRaw() + ")보다 낮은 버전입니다. "
                    + "이미 마이그레이션된 DB 에는 outOfOrder 설정 없이는 적용되지 않습니다.");
        }
    }

    /** 이번에 커밋하지 않는 브랜치에 같은 버전이 있는지 확인한다. */
    private static void checkOtherBranches(FlywayMigration candidate, List<String> targets, List<BranchSnapshot> snapshots,
                                           List<String> warnings, List<String> notes) {
        List<String> different = new ArrayList<>();
        List<String> same = new ArrayList<>();
        int failed = 0;
        for (BranchSnapshot s : snapshots) {
            if (targets.contains(s.name())) continue;
            if (!s.ok()) {
                failed++;
                continue;
            }
            for (FlywayMigration m : s.versioned()) {
                if (!m.version().equals(candidate.version())) continue;
                if (m.variantId().equals(candidate.variantId())) same.add(s.name());
                else different.add(s.name() + " (" + m.fileName() + ")");
            }
        }
        if (!different.isEmpty()) {
            warnings.add("다른 브랜치에 같은 버전인데 파일명 또는 SQL 이 다른 파일이 있습니다: " + listed(different)
                    + ". 커밋하면 대시보드에 '충돌 위험'으로 표시됩니다.");
        }
        if (!same.isEmpty()) {
            notes.add("같은 파일(파일명·내용 동일)이 이미 있는 브랜치: " + listed(same));
        }
        if (failed > 0) {
            warnings.add(failed + "개 브랜치는 조회하지 못해 순번 계산과 충돌 확인에서 빠졌습니다.");
        }
    }

    private static String listed(List<String> names) {
        if (names.size() <= MAX_LISTED_BRANCHES) return String.join(", ", names);
        return String.join(", ", names.subList(0, MAX_LISTED_BRANCHES)) + " 외 " + (names.size() - MAX_LISTED_BRANCHES) + "개";
    }

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }

    private static String joinPath(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    /** Git blob SHA-1. GitLab 트리 API 의 id 와 같은 값이라 다른 브랜치 파일과 내용이 같은지 바로 비교할 수 있다. */
    static String blobSha(byte[] content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha1.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
