package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.FlywayMigration;
import com.branchradar.dto.FlywayVersion;
import com.branchradar.service.FlywayLocalStore.Draft;
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
 * Flyway 관리: 작성 중인 Flyway 의 임시저장과, 여러 브랜치에 새 Flyway 파일을 추가하는 커밋.
 * 이 도구의 유일한 쓰기 기능이다.
 * <p>
 * 작성 중인 Flyway 는 작업자 모두가 함께 이어 쓰는 한 개다. 저장/커밋할 때마다 revision 을 확인해서,
 * 다른 사람이 먼저 저장한 내용을 모르고 덮어쓰거나 빼고 커밋하는 일을 막는다.
 * <p>
 * 파일명은 직접 정하지 않는다: V{오늘 yyMMdd}.{전체 브랜치 기준 다음 순번 3자리}__{설정값}.sql
 */
@Service
public class FlywayCommitService {

    /** 커밋 등록자 (고정) */
    public static final List<String> WORKERS = List.of("jwsung", "ejkim", "jwhan", "chwonhan00");

    /** 커밋 제목 앞머리: 새 이모지 + 공백 한 칸 */
    static final String SUMMARY_PREFIX = new String(Character.toChars(0x1F426)) + " ";

    static final int MAX_SQL_BYTES = 1024 * 1024;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_LISTED_BRANCHES = 5;
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

    // ================================================================ 입출력

    /**
     * 화면에서 보낸 내용 (임시저장 · 검증 · 커밋 공통).
     *
     * @param description 커밋 메시지의 '설명' (선택)
     * @param worker      등록자. 커밋할 때는 필수
     * @param revision    화면이 마지막으로 본 임시저장의 revision (임시저장이 없었으면 0 또는 null)
     */
    public record Request(String sql, String description, String worker, List<String> branches, Long revision) {
    }

    /** 화면에 보여 줄 임시저장 상태. exists=false 면 작성 중인 것이 없다. */
    public record DraftView(boolean exists, String sql, String description, String worker, List<String> branches,
                            long revision, String updatedAt, String pinnedVersion, int lines) {

        static DraftView of(Draft d) {
            if (d == null) return new DraftView(false, "", "", "", List.of(), 0, null, null, 0);
            String sql = d.sql() == null ? "" : d.sql();
            return new DraftView(true, sql, d.description() == null ? "" : d.description(),
                    d.worker() == null ? "" : d.worker(),
                    d.branches() == null ? List.of() : d.branches(), d.revision(), d.updatedAt(), d.pinnedVersion(),
                    sql.isBlank() ? 0 : (int) sql.strip().lines().count());
        }
    }

    /** 브랜치 하나에 대한 검증 결과 */
    public record BranchPlan(String branch, String headSha, boolean protectedBranch,
                             List<String> errors, List<String> warnings) {
    }

    /**
     * 검증 결과. ok 여야 커밋할 수 있다.
     *
     * @param errors   브랜치와 무관한 오류 (등록자 미선택, SQL 없음 등)
     * @param warnings 커밋은 가능하지만 확인이 필요한 사항
     * @param notes    참고 정보
     */
    public record Plan(String fileName, String filePath, String commitMessage, String worker,
                       int sqlBytes, int sqlLines,
                       List<String> errors, List<String> warnings, List<String> notes,
                       List<BranchPlan> branches, boolean ok) {
    }

    public record BranchResult(String branch, boolean success, String sha, String shortSha, String webUrl, String error) {
    }

    /**
     * @param allSucceeded 선택한 모든 브랜치에 커밋되었는지. false 면 실패한 브랜치만 남긴 임시저장이 유지된다
     * @param localFile    로컬 폴더에 저장한 파일 경로 (저장하지 못했으면 null, 사유는 localError)
     */
    public record Committed(String fileName, String filePath, List<BranchResult> results, boolean allSucceeded,
                            String localFile, String localError, DraftView draft) {
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

    /** 화면이 보고 있던 임시저장이 최신이 아닐 때 (다른 사람이 먼저 저장했거나 이미 커밋됨) */
    public static class DraftConflictException extends RuntimeException {
        private final transient DraftView current;

        public DraftConflictException(String message, DraftView current) {
            super(message);
            this.current = current;
        }

        public DraftView getCurrent() {
            return current;
        }
    }

    private record Prepared(Plan plan, String content, String version) {
    }

    // ================================================================ 임시저장

    public DraftView draft() {
        return DraftView.of(store.readDraft());
    }

    /** SQL 과 설명이 모두 비어 있으면 임시저장을 지운다. */
    public synchronized DraftView saveDraft(Request r) {
        Draft current = store.readDraft();
        checkRevision(r, current);

        String sql = r.sql() == null ? "" : r.sql();
        String description = text(r.description());
        if (sql.isBlank() && description.isEmpty()) {
            // 일부 브랜치에만 커밋된 상태(버전 고정)라면 지우지 않는다
            if (current == null || current.pinnedVersion() == null) {
                store.deleteDraft();
                return DraftView.of(null);
            }
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            throw new IllegalArgumentException("SQL 이 너무 큽니다 (최대 " + MAX_SQL_BYTES / 1024 + "KB).");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("설명이 너무 깁니다 (최대 " + MAX_DESCRIPTION + "자).");
        }
        String worker = text(r.worker());
        Draft next = new Draft(sql, description, WORKERS.contains(worker) ? worker : "", cleanBranches(r.branches()),
                nextRevision(current), now().format(STAMP), current == null ? null : current.pinnedVersion());
        store.writeDraft(next);
        return DraftView.of(next);
    }

    private void checkRevision(Request r, Draft current) {
        long expected = current == null ? 0 : current.revision();
        long got = r.revision() == null ? 0 : r.revision();
        if (expected == got) return;
        if (current == null) {
            throw new DraftConflictException("보고 있던 임시저장은 이미 커밋되었거나 지워졌습니다. "
                    + "새로고침해서 Flyway 목록을 확인하세요.", DraftView.of(null));
        }
        String who = current.worker() == null || current.worker().isBlank() ? "다른 사람" : current.worker();
        throw new DraftConflictException(who + " 이(가) " + current.updatedAt() + " 에 먼저 저장했습니다. "
                + "지금 내용을 따로 복사해 두고, 새로고침해서 최신 내용에 이어서 작성하세요.", DraftView.of(current));
    }

    /** 항상 커지는 값. 임시저장이 지워졌다 다시 생겨도 예전 화면의 revision 과 겹치지 않게 시각을 쓴다. */
    private long nextRevision(Draft current) {
        long now = clock.millis();
        return current == null ? now : Math.max(now, current.revision() + 1);
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

    // ================================================================ 파일명

    /**
     * 지금 커밋하면 쓰게 될 파일명. 일부 브랜치에만 커밋된 임시저장이 있으면 그때의 버전을 그대로 쓴다.
     *
     * @param all 전체 브랜치의 버전
     */
    public String expectedFileName(Collection<FlywayVersion> all) {
        Draft current = store.readDraft();
        String version = current != null && current.pinnedVersion() != null
                ? current.pinnedVersion() : nextVersion(all, now().toLocalDate());
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

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(properties.staleBranch().zoneId()));
    }

    // ================================================================ 검증 · 커밋

    /** 커밋하지 않고 검증만 한다. 브랜치 목록 조회 실패 시 GitLabException. */
    public Plan plan(Request request) {
        return prepare(request).plan();
    }

    /**
     * 검증 후 선택한 브랜치마다 같은 파일을 추가하는 커밋을 만든다.
     * 하나라도 검증 오류가 있으면 어느 브랜치에도 커밋하지 않는다.
     * 커밋 도중 일부 브랜치만 GitLab 이 거부하면, 성공한 브랜치는 그대로 두고 실패한 브랜치만 임시저장에 남긴다.
     * 이때 버전을 고정해서 다시 커밋해도 같은 파일명이 쓰이게 한다.
     *
     * @throws DraftConflictException 다른 사람이 먼저 저장한 내용이 있을 때 (그 내용을 빼고 커밋하지 않도록)
     * @throws InvalidDraftException  검증 오류가 있을 때
     */
    public synchronized Committed commit(Request request) {
        Draft current = store.readDraft();
        checkRevision(request, current);

        Prepared p = prepare(request);
        Plan plan = p.plan();
        if (!plan.ok()) {
            throw new InvalidDraftException(plan);
        }

        List<BranchResult> results = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (BranchPlan bp : plan.branches()) {
            try {
                GitLabClient.Commit c = client.createFileCommit(bp.branch(), plan.filePath(), p.content(),
                        plan.commitMessage(), null, null);
                String sha = c == null ? null : c.id();
                String shortSha = c == null ? null
                        : c.shortId() != null ? c.shortId()
                        : sha == null ? null : sha.substring(0, Math.min(8, sha.length()));
                results.add(new BranchResult(bp.branch(), true, sha, shortSha, c == null ? null : c.webUrl(), null));
            } catch (GitLabException e) {
                failed.add(bp.branch());
                results.add(new BranchResult(bp.branch(), false, null, null, null, e.getMessage()));
            }
        }
        boolean anySucceeded = failed.size() < results.size();

        String localFile = null;
        String localError = null;
        if (anySucceeded) {
            String relative = flywayService.relativePath(plan.filePath());
            try {
                store.write(relative, p.content().getBytes(StandardCharsets.UTF_8), true);
                localFile = store.resolve(relative).toString();
            } catch (Exception e) {
                localError = "커밋은 되었지만 로컬 폴더에 저장하지 못했습니다: " + e.getMessage();
            }
        }

        if (failed.isEmpty()) {
            store.deleteDraft();
        } else if (anySucceeded) {
            store.writeDraft(new Draft(request.sql(), text(request.description()), plan.worker(), failed,
                    nextRevision(current), now().format(STAMP), p.version()));
        }
        return new Committed(plan.fileName(), plan.filePath(), results, failed.isEmpty(), localFile, localError, draft());
    }

    private Prepared prepare(Request r) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (!properties.gitlab().hasToken()) {
            errors.add("GitLab 토큰이 설정되지 않아 커밋할 수 없습니다 (application.yml 의 branch-radar.gitlab.token).");
        }
        String worker = text(r.worker());
        if (!WORKERS.contains(worker)) {
            errors.add("등록자를 선택하세요.");
        }
        String description = text(r.description());
        if (description.length() > MAX_DESCRIPTION) {
            errors.add("설명이 너무 깁니다 (최대 " + MAX_DESCRIPTION + "자).");
        }

        // SQL: 줄바꿈은 LF 로 통일하고 파일 끝에 줄바꿈을 붙인다
        String content = r.sql() == null ? "" : r.sql().replace("\r\n", "\n").replace('\r', '\n');
        if (content.isBlank()) {
            errors.add("SQL 을 입력하세요.");
        } else if (!content.endsWith("\n")) {
            content += "\n";
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

        // 브랜치 목록을 지금 다시 조회해서 최신 커밋 기준으로 판단한다
        List<GitLabClient.Branch> branches = client.listBranches().items();
        List<BranchSnapshot> snapshots = branchService.snapshots(branches);
        Map<String, GitLabClient.Branch> branchByName = new HashMap<>();
        branches.forEach(b -> branchByName.put(b.name(), b));
        Map<String, BranchSnapshot> snapshotByName = new HashMap<>();
        snapshots.forEach(s -> snapshotByName.put(s.name(), s));

        Draft current = store.readDraft();
        String version = current != null && current.pinnedVersion() != null
                ? current.pinnedVersion() : nextVersion(versions(snapshots), now().toLocalDate());
        String fileName = fileName(version);
        String filePath = joinPath(cfg.normalizedPath(), baseDirectory(snapshotByName.get(properties.branch().base())), fileName);
        String message = commitMessage(fileName, worker, description);

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
        Plan plan = new Plan(fileName, filePath, message, worker, bytes.length, lines,
                List.copyOf(errors), List.copyOf(warnings), List.copyOf(notes), List.copyOf(plans), ok);
        return new Prepared(plan, content, version);
    }

    /**
     * 커밋 메시지. 제목과 본문 양식은 고정이다.
     * <pre>
     * 🐦 V261102.001__VADA.sql
     *
     * 등록자 : jwhan
     * 설명 : ...
     * </pre>
     */
    static String commitMessage(String fileName, String worker, String description) {
        return SUMMARY_PREFIX + fileName + "\n\n등록자 : " + worker + "\n" + ("설명 : " + description).stripTrailing();
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
