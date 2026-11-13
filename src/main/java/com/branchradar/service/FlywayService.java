package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.BranchStatus;
import com.branchradar.dto.BranchStatus.Status;
import com.branchradar.dto.CompareResult.Conflict;
import com.branchradar.dto.CompareResult.FlywayCompare;
import com.branchradar.dto.CompareResult.Item;
import com.branchradar.dto.CompareResult.RepeatableDiff;
import com.branchradar.dto.DashboardView.LatestInfo;
import com.branchradar.dto.DashboardView.Variant;
import com.branchradar.dto.FlywayMigration;
import com.branchradar.dto.FlywayVersion;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Flyway 파일 조회 · 파일명 해석 · 브랜치 간 비교.
 * 비교/분석 메서드는 GitLab 호출 없이 동작하므로 테스트 데이터로 검증할 수 있다.
 */
@Service
public class FlywayService {

    public static final Comparator<FlywayMigration> BY_VERSION =
            Comparator.comparing(FlywayMigration::version).thenComparing(FlywayMigration::fileName);

    private static final int TREE_CACHE_SIZE = 2000;

    private final GitLabProperties.Flyway cfg;
    private final GitLabClient client;

    /**
     * 커밋 SHA → 마이그레이션 목록. 커밋 내용은 바뀌지 않으므로 오래 보관해도 결과가 틀려지지 않는다.
     * 브랜치에 새 커밋이 생기면 SHA 가 바뀌어 자연히 새로 조회된다.
     */
    private final Map<String, MigrationSet> treeCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MigrationSet> eldest) {
                    return size() > TREE_CACHE_SIZE;
                }
            });

    public FlywayService(GitLabProperties properties, GitLabClient client) {
        this.cfg = properties.flyway();
        this.client = client;
    }

    public record MigrationSet(
            List<FlywayMigration> versioned,
            List<FlywayMigration> repeatable,
            List<String> ignored,
            boolean dirMissing) {
    }

    public String migrationPath() {
        return cfg.normalizedPath();
    }

    /** 저장소 경로를 마이그레이션 디렉터리 기준 상대 경로로 바꾼다 (하위 폴더 유지). */
    public String relativePath(String path) {
        String root = cfg.normalizedPath();
        return !root.isEmpty() && path.startsWith(root + "/") ? path.substring(root.length() + 1) : path;
    }

    // ================================================================ 조회

    /** 특정 커밋의 마이그레이션 목록. 디렉터리가 없으면 빈 목록(dirMissing=true). */
    public MigrationSet loadMigrations(String commitSha) {
        MigrationSet cached = treeCache.get(commitSha);
        if (cached != null) return cached;

        MigrationSet set;
        try {
            GitLabClient.Paged<GitLabClient.TreeEntry> tree = client.listTree(cfg.normalizedPath(), commitSha);
            if (!tree.complete()) {
                throw new GitLabException(0, "마이그레이션 디렉터리 파일이 너무 많아 일부만 조회되었습니다 (max-pages 초과). "
                        + "결과가 불완전하므로 분석하지 않습니다.", null);
            }
            set = fromTree(tree.items());
        } catch (GitLabException e) {
            if (!e.isNotFound()) throw e;
            set = new MigrationSet(List.of(), List.of(), List.of(), true);
        }
        treeCache.put(commitSha, set);
        return set;
    }

    public MigrationSet fromTree(List<GitLabClient.TreeEntry> entries) {
        List<FlywayMigration> versioned = new ArrayList<>();
        List<FlywayMigration> repeatable = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (GitLabClient.TreeEntry e : entries) {
            if (!"blob".equals(e.type())) continue;
            try {
                FlywayMigration m = parse(e.path(), e.id());
                if (m == null) continue;
                (m.isRepeatable() ? repeatable : versioned).add(m);
            } catch (IllegalArgumentException ex) {
                ignored.add(e.name() + " (" + ex.getMessage() + ")");
            }
        }
        versioned.sort(BY_VERSION);
        repeatable.sort(Comparator.comparing(FlywayMigration::fileName));
        return new MigrationSet(List.copyOf(versioned), List.copyOf(repeatable), List.copyOf(ignored), false);
    }

    /**
     * 파일 경로를 Flyway 마이그레이션으로 해석한다.
     *
     * @return SQL 접미사가 아니면 null
     * @throws IllegalArgumentException SQL 파일이지만 Flyway 이름 규칙에 맞지 않을 때
     */
    public FlywayMigration parse(String path, String blobId) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String suffix = cfg.suffixes().stream()
                .filter(s -> name.toLowerCase().endsWith(s.toLowerCase()))
                .findFirst().orElse(null);
        if (suffix == null) return null;

        String base = name.substring(0, name.length() - suffix.length());
        String sep = cfg.separator();

        if (base.startsWith(cfg.repeatablePrefix() + sep)) {
            String desc = base.substring(cfg.repeatablePrefix().length() + sep.length());
            return new FlywayMigration(FlywayMigration.Type.REPEATABLE, null, desc.replace('_', ' '), name, path, blobId);
        }
        if (base.startsWith(cfg.versionedPrefix())) {
            String rest = base.substring(cfg.versionedPrefix().length());
            int idx = rest.indexOf(sep);
            if (idx <= 0) {
                throw new IllegalArgumentException("버전/설명 구분자 '" + sep + "' 없음");
            }
            FlywayVersion version = FlywayVersion.parse(rest.substring(0, idx), cfg.dateAwareOrdering());
            String desc = rest.substring(idx + sep.length()).replace('_', ' ');
            return new FlywayMigration(FlywayMigration.Type.VERSIONED, version, desc, name, path, blobId);
        }
        throw new IllegalArgumentException("Flyway 접두어 불일치");
    }

    // ================================================================ 대시보드 분석

    public record DashboardAnalysis(List<BranchStatus> rows, LatestInfo latest, String latestRefBranch) {
    }

    /**
     * 전체 브랜치 분석.
     * <ul>
     *   <li>전체 최신 = 모든 브랜치의 버전 마이그레이션 중 버전이 가장 큰 것 (커밋 날짜와 무관)</li>
     *   <li>충돌 = 같은 버전에 파일명 또는 내용(blob)이 다른 파일이 존재.
     *       기준 브랜치가 그 버전을 갖고 있으면 기준 브랜치와 다른 브랜치만 충돌로 표시하고,
     *       기준 브랜치에 없는 버전이면 해당 버전을 가진 모든 브랜치를 충돌로 표시한다.</li>
     *   <li>브랜치 내부 버전 중복 = Flyway 실행 시 실제 오류가 나는 상황 (뒤처짐과 구분)</li>
     * </ul>
     */
    public DashboardAnalysis analyze(List<BranchSnapshot> snapshots, String baseName) {
        List<BranchSnapshot> ok = snapshots.stream().filter(BranchSnapshot::ok).toList();
        BranchSnapshot base = ok.stream().filter(s -> s.name().equals(baseName)).findFirst().orElse(null);

        // 버전 key → 변형(파일명|blob) → 브랜치 목록
        Map<String, Map<String, List<BranchSnapshot>>> variants = new HashMap<>();
        for (BranchSnapshot s : ok) {
            for (FlywayMigration m : s.versioned()) {
                variants.computeIfAbsent(m.version().getKey(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(m.variantId(), k -> new ArrayList<>())
                        .add(s);
            }
        }
        Map<String, String> baseVariant = base == null ? Map.of() : singleVariants(base);

        FlywayMigration latest = ok.stream().flatMap(s -> s.versioned().stream())
                .max(BY_VERSION).orElse(null);
        String latestKey = latest == null ? null : latest.version().getKey();

        BranchSnapshot latestRef = null;
        if (latestKey != null) {
            if (base != null && hasKey(base, latestKey)) {
                latestRef = base;
            } else {
                latestRef = ok.stream().filter(s -> hasKey(s, latestKey))
                        .max(Comparator.comparing(BranchSnapshot::commitDate,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .orElse(null);
            }
        }

        List<BranchStatus> rows = new ArrayList<>();
        for (BranchSnapshot s : snapshots) {
            rows.add(statusOf(s, base, variants, baseVariant, latest, latestRef));
        }
        rows.sort(Comparator.comparing((BranchStatus r) -> !r.base())
                .thenComparing(r -> r.branch().commitDate(), Comparator.nullsLast(Comparator.reverseOrder())));

        LatestInfo latestInfo = null;
        if (latestKey != null) {
            List<Variant> vs = new ArrayList<>();
            variants.get(latestKey).forEach((variantId, branches) -> {
                BranchSnapshot first = branches.get(0);
                FlywayMigration m = first.versioned().stream()
                        .filter(x -> x.variantId().equals(variantId)).findFirst().orElseThrow();
                vs.add(new Variant(m, first.commitSha(), branches.stream().map(BranchSnapshot::name).toList()));
            });
            latestInfo = new LatestInfo(latest.version().getRaw(), vs.size() > 1, vs);
        }
        return new DashboardAnalysis(rows, latestInfo, latestRef == null ? null : latestRef.name());
    }

    private BranchStatus statusOf(BranchSnapshot s, BranchSnapshot base,
                                  Map<String, Map<String, List<BranchSnapshot>>> variants,
                                  Map<String, String> baseVariant,
                                  FlywayMigration latest, BranchSnapshot latestRef) {
        boolean isBase = base != null && base.name().equals(s.name());
        if (!s.ok()) {
            return new BranchStatus(s, Status.ERROR, null, List.of(), List.of(), null, null, 0, isBase, s.error());
        }
        if (s.versioned().isEmpty()) {
            String msg = s.dirMissing() ? "마이그레이션 디렉터리가 없습니다." : "버전 Flyway 파일이 없습니다.";
            return new BranchStatus(s, Status.NO_FILES, null, List.of(), List.of(),
                    base == null || isBase ? null : base.versioned().size(), null, 0, isBase, msg);
        }

        List<String> duplicates = duplicateVersions(s);
        Set<String> conflicts = new LinkedHashSet<>();
        for (FlywayMigration m : s.versioned()) {
            String key = m.version().getKey();
            if (variants.get(key).size() > 1) {
                String ref = baseVariant.get(key);
                if (ref == null || !ref.equals(m.variantId())) {
                    conflicts.add(m.version().getRaw());
                }
            }
        }

        String latestKey = latest.version().getKey();
        String latestRefVariant = baseVariant.get(latestKey);
        boolean hasLatest = s.versioned().stream().anyMatch(m -> m.version().getKey().equals(latestKey)
                && (latestRefVariant == null || latestRefVariant.equals(m.variantId())));

        Set<String> keys = keys(s);
        Integer missingVsBase = null;
        Integer extraVsBase = null;
        if (base != null && !isBase) {
            Set<String> baseKeys = keys(base);
            missingVsBase = (int) baseKeys.stream().filter(k -> !keys.contains(k)).count();
            extraVsBase = (int) keys.stream().filter(k -> !baseKeys.contains(k)).count();
        }
        int missingVsLatestRef = latestRef == null ? 0
                : (int) keys(latestRef).stream().filter(k -> !keys.contains(k)).count();

        Status status;
        List<String> msgs = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            msgs.add("브랜치 안에 같은 버전이 여러 개 있습니다 (" + String.join(", ", duplicates)
                    + "). Flyway 실행 시 'more than one migration' 오류가 발생합니다.");
        }
        if (!conflicts.isEmpty()) {
            msgs.add("같은 버전인데 다른 브랜치와 파일명 또는 SQL 내용이 다릅니다: " + String.join(", ", conflicts));
        }
        if (!hasLatest) {
            msgs.add("전체 최신 Flyway(" + latest.version().getRaw() + ")가 없습니다. 현재 최신: "
                    + s.latest().version().getRaw()
                    + ". 의도적으로 이전 버전을 유지하는 브랜치일 수 있습니다.");
        }
        if (!duplicates.isEmpty() || !conflicts.isEmpty()) {
            status = Status.CONFLICT;
        } else if (!hasLatest) {
            status = Status.BEHIND;
        } else {
            status = Status.UP_TO_DATE;
            msgs.add("전체 최신 Flyway 를 동일한 내용으로 포함합니다.");
        }
        return new BranchStatus(s, status, s.latest(), List.copyOf(conflicts), duplicates,
                missingVsBase, extraVsBase, missingVsLatestRef, isBase, String.join(" ", msgs));
    }

    private static Set<String> keys(BranchSnapshot s) {
        return s.versioned().stream().map(m -> m.version().getKey()).collect(Collectors.toSet());
    }

    private static boolean hasKey(BranchSnapshot s, String key) {
        return s.versioned().stream().anyMatch(m -> m.version().getKey().equals(key));
    }

    /** 버전별로 파일이 정확히 하나인 경우의 변형 ID */
    private static Map<String, String> singleVariants(BranchSnapshot s) {
        Map<String, List<FlywayMigration>> byKey = groupByKey(s.versioned());
        Map<String, String> result = new HashMap<>();
        byKey.forEach((k, list) -> {
            if (list.size() == 1) result.put(k, list.get(0).variantId());
        });
        return result;
    }

    static List<String> duplicateVersions(BranchSnapshot s) {
        return groupByKey(s.versioned()).values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> list.stream().map(FlywayMigration::fileName).collect(Collectors.joining(" / ")))
                .toList();
    }

    private static Map<String, List<FlywayMigration>> groupByKey(List<FlywayMigration> list) {
        Map<String, List<FlywayMigration>> map = new LinkedHashMap<>();
        for (FlywayMigration m : list) {
            map.computeIfAbsent(m.version().getKey(), k -> new ArrayList<>()).add(m);
        }
        return map;
    }

    // ================================================================ A/B 비교

    /**
     * A/B Flyway 비교. 버전 · 파일명 · 내용(blob) 을 모두 비교한다.
     */
    public FlywayCompare compare(BranchSnapshot a, BranchSnapshot b) {
        Map<String, List<FlywayMigration>> ma = groupByKey(a.versioned());
        Map<String, List<FlywayMigration>> mb = groupByKey(b.versioned());

        List<Item> onlyInA = onlyIn(ma, mb, b, "B");
        List<Item> onlyInB = onlyIn(mb, ma, a, "A");
        List<Item> same = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();

        for (Map.Entry<String, List<FlywayMigration>> e : ma.entrySet()) {
            List<FlywayMigration> lb = mb.get(e.getKey());
            if (lb == null) continue;
            List<FlywayMigration> la = e.getValue();
            Set<String> va = la.stream().map(FlywayMigration::variantId).collect(Collectors.toSet());
            Set<String> vb = lb.stream().map(FlywayMigration::variantId).collect(Collectors.toSet());
            if (va.equals(vb)) {
                same.add(new Item(la.get(0), null));
            } else {
                FlywayMigration x = la.get(0);
                FlywayMigration y = lb.stream().filter(m -> m.fileName().equals(x.fileName())).findFirst().orElse(lb.get(0));
                conflicts.add(new Conflict(x.version().getRaw(), x, y, conflictReason(x, y), null, null, null));
            }
        }

        return new FlywayCompare(onlyInA, onlyInB, same, conflicts,
                duplicateVersions(a), duplicateVersions(b), compareRepeatables(a, b));
    }

    static String conflictReason(FlywayMigration x, FlywayMigration y) {
        boolean nameDiff = !x.fileName().equals(y.fileName());
        boolean contentDiff = !x.blobId().equals(y.blobId());
        if (nameDiff && contentDiff) return "같은 버전에 서로 다른 마이그레이션 (파일명·SQL 모두 다름)";
        if (nameDiff) return "SQL 내용은 같고 파일명(설명)만 다름 — 이미 적용된 DB에서는 Flyway validate 시 description 불일치 가능";
        return "같은 파일명이지만 SQL 내용이 다름";
    }

    private static List<Item> onlyIn(Map<String, List<FlywayMigration>> mine, Map<String, List<FlywayMigration>> other,
                                     BranchSnapshot otherSnap, String otherName) {
        Map<String, FlywayMigration> otherByBlob = new HashMap<>();
        other.values().forEach(list -> list.forEach(m -> otherByBlob.putIfAbsent(m.blobId(), m)));
        FlywayMigration otherLatest = otherSnap.latest();

        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, List<FlywayMigration>> e : mine.entrySet()) {
            if (other.containsKey(e.getKey())) continue;
            for (FlywayMigration m : e.getValue()) {
                List<String> notes = new ArrayList<>();
                FlywayMigration sameContent = otherByBlob.get(m.blobId());
                if (sameContent != null) {
                    notes.add(otherName + "에 같은 SQL 내용이 다른 버전(" + sameContent.fileName() + ")으로 존재 — 버전 변경 여부 확인");
                }
                if (otherLatest != null && m.version().compareTo(otherLatest.version()) < 0) {
                    notes.add(otherName + "의 최신(" + otherLatest.version().getRaw() + ")보다 낮은 버전 — "
                            + otherName + " 기준으로 이미 마이그레이션된 DB에 적용하려면 outOfOrder 설정이 필요할 수 있음");
                }
                result.add(new Item(m, notes.isEmpty() ? null : String.join(" / ", notes)));
            }
        }
        return result;
    }

    private static List<RepeatableDiff> compareRepeatables(BranchSnapshot a, BranchSnapshot b) {
        Map<String, FlywayMigration> ra = new TreeMap<>();
        Map<String, FlywayMigration> rb = new TreeMap<>();
        a.repeatable().forEach(m -> ra.put(m.fileName(), m));
        b.repeatable().forEach(m -> rb.put(m.fileName(), m));
        Set<String> names = new java.util.TreeSet<>(ra.keySet());
        names.addAll(rb.keySet());
        List<RepeatableDiff> diffs = new ArrayList<>();
        for (String n : names) {
            FlywayMigration x = ra.get(n);
            FlywayMigration y = rb.get(n);
            if (x != null && y == null) diffs.add(new RepeatableDiff(n, x, null, "A에만 있음"));
            else if (x == null) diffs.add(new RepeatableDiff(n, null, y, "B에만 있음"));
            else if (!Objects.equals(x.blobId(), y.blobId()))
                diffs.add(new RepeatableDiff(n, x, y, "내용 다름 (Repeatable 은 내용이 바뀌면 재실행됨)"));
        }
        return diffs;
    }

    // ================================================================ Flyway checksum

    /**
     * Flyway 의 ChecksumCalculator 와 같은 방식(줄 단위 CRC32, 줄바꿈 문자 제외, 첫 줄 BOM 제거, UTF-8)으로
     * 계산한 값. 줄바꿈(CRLF/LF)만 다른 파일은 같은 값이 나온다.
     * 인코딩 설정이 UTF-8 이 아니면 실제 Flyway 값과 다를 수 있으므로 참고용이다.
     */
    public static int flywayChecksum(byte[] content) {
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null) {
                if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                do {
                    crc.update(line.getBytes(StandardCharsets.UTF_8));
                } while ((line = reader.readLine()) != null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return (int) crc.getValue();
    }
}
