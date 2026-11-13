package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.config.GitLabProperties.TagSort;
import com.branchradar.dto.TagInfo;
import com.branchradar.dto.TagSummary;
import com.branchradar.dto.TagSummary.BranchTag;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab 태그 기반 패키지 버전 조회.
 * <p>
 * 브랜치별 최근 태그 판정: 태그를 정렬 정책상 최신 순으로 훑으면서, 각 태그 커밋을 "포함하는 브랜치" 를
 * GitLab 에 물어본다(커밋 SHA 당 1회). 처음 포함이 확인된 태그가 그 브랜치의 최근 태그다.
 * 모든 브랜치가 판정되면 즉시 멈추므로 보통 몇 번의 호출로 끝난다.
 */
@Service
public class TagService {

    /** 1.2.3, 1.2, 20250212, 1.2.3-rc.1 등 */
    private static final Pattern DEFAULT_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)");

    private final GitLabProperties.Tag cfg;
    private final GitLabClient client;
    private final Pattern versionPattern;

    public TagService(GitLabProperties properties, GitLabClient client) {
        this.cfg = properties.tag();
        this.client = client;
        String p = cfg.versionPattern();
        this.versionPattern = p == null || p.isBlank() ? DEFAULT_VERSION : Pattern.compile(p);
    }

    public TagSummary summarize(Collection<String> branchNames) {
        if (!cfg.enabled()) return TagSummary.disabled();
        String policy = cfg.sort().name().toLowerCase();

        GitLabClient.Paged<GitLabClient.Tag> page;
        try {
            page = client.listTags();
        } catch (GitLabException e) {
            return new TagSummary(true, null, 0, policy, Map.of(), "태그 조회 실패: " + e.getMessage(), List.of());
        }
        List<TagInfo> tags = new ArrayList<>(page.items().stream().map(this::toInfo).toList());
        tags.sort(latestFirst());

        if (!page.complete()) {
            // 일부 태그만 받았으면 최신 판정도, 브랜치별 판정도 틀릴 수 있으므로 하지 않는다
            return new TagSummary(true, null, tags.size(), policy, Map.of(), null,
                    List.of("태그가 너무 많아 일부(" + tags.size() + "개)만 조회되었습니다. 최신 태그와 브랜치별 태그는 '확인 불가'로 표시합니다."));
        }
        List<String> notices = new ArrayList<>();
        Map<String, BranchTag> byBranch = assign(tags, branchNames, client::commitBranches, cfg.maxLookups(), notices);
        TagInfo latest = tags.isEmpty() ? null : tags.get(0);
        if (latest != null && cfg.sort() == TagSort.SEMVER && latest.version() == null) {
            notices.add("태그 이름에서 버전을 찾지 못해 최신 태그를 판단할 수 없습니다. tag.version-pattern 또는 tag.sort 설정을 확인하세요.");
            latest = null;
        }
        return new TagSummary(true, latest, tags.size(), policy, byBranch, null, notices);
    }

    TagInfo toInfo(GitLabClient.Tag t) {
        String commitSha = t.commit() != null ? t.commit().id() : t.target();
        boolean annotated = t.target() != null && commitSha != null && !t.target().equals(commitSha);
        return new TagInfo(t.name(), extractVersion(t.name()), commitSha,
                t.commit() == null ? null : t.commit().committedDate(),
                t.createdAt(), annotated);
    }

    String extractVersion(String tagName) {
        Matcher m = versionPattern.matcher(tagName);
        if (!m.find()) return null;
        return m.groupCount() >= 1 ? m.group(1) : m.group();
    }

    /** 정렬 정책상 최신이 앞에 오는 비교자 */
    Comparator<TagInfo> latestFirst() {
        Comparator<TagInfo> byName = Comparator.comparing(TagInfo::name);
        return switch (cfg.sort()) {
            case SEMVER -> Comparator.comparing(TagInfo::version,
                            Comparator.nullsFirst(TagService::compareVersions))
                    .thenComparing(byName).reversed();
            case CREATED -> Comparator.comparing(TagInfo::createdAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(byName).reversed();
            case NAME -> byName.reversed();
        };
    }

    /**
     * 버전 비교 (SemVer 방식). 숫자 부분은 정수 비교(없는 자리는 0),
     * 같으면 정식 버전 &gt; 사전 릴리스(-rc.1 등), 사전 릴리스끼리는 점 단위로 비교한다. '+' 뒤 빌드 메타데이터는 무시.
     */
    static int compareVersions(String a, String b) {
        String[] pa = splitPre(a);
        String[] pb = splitPre(b);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            int c = num(na, i).compareTo(num(nb, i));
            if (c != 0) return c;
        }
        if (pa[1] == null || pb[1] == null) {
            return pa[1] == null ? (pb[1] == null ? 0 : 1) : -1;
        }
        String[] xa = pa[1].split("\\.");
        String[] xb = pb[1].split("\\.");
        for (int i = 0; i < Math.min(xa.length, xb.length); i++) {
            boolean da = xa[i].chars().allMatch(Character::isDigit) && !xa[i].isEmpty();
            boolean db = xb[i].chars().allMatch(Character::isDigit) && !xb[i].isEmpty();
            int c;
            if (da && db) c = new BigInteger(xa[i]).compareTo(new BigInteger(xb[i]));
            else if (da) c = -1;
            else if (db) c = 1;
            else c = xa[i].compareTo(xb[i]);
            if (c != 0) return c;
        }
        return Integer.compare(xa.length, xb.length);
    }

    private static String[] splitPre(String v) {
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:[-.]?(.+))?$").matcher(v);
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        return new String[]{"0", v};
    }

    private static BigInteger num(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) {
            return BigInteger.ZERO;
        }
        return new BigInteger(parts[i]);
    }

    /**
     * @param sortedTags        최신 순 정렬된 태그
     * @param containingBranches 커밋 SHA → 그 커밋을 포함하는 브랜치 (GitLab 조회)
     * @param maxLookups        최대 조회 횟수
     */
    static Map<String, BranchTag> assign(List<TagInfo> sortedTags, Collection<String> branchNames,
                                         Function<String, GitLabClient.Paged<GitLabClient.Ref>> containingBranches,
                                         int maxLookups, List<String> notices) {
        Map<String, BranchTag> result = new HashMap<>();
        Set<String> pending = new LinkedHashSet<>(branchNames);
        Map<String, Set<String>> looked = new HashMap<>();
        String stopReason = null;

        for (TagInfo tag : sortedTags) {
            if (pending.isEmpty()) break;
            if (tag.commitSha() == null) continue;
            Set<String> branches = looked.get(tag.commitSha());
            if (branches == null) {
                if (looked.size() >= maxLookups) {
                    stopReason = "조회 상한(max-lookups=" + maxLookups + ")에 도달";
                    break;
                }
                try {
                    GitLabClient.Paged<GitLabClient.Ref> refs = containingBranches.apply(tag.commitSha());
                    if (!refs.complete()) {
                        stopReason = "태그 " + tag.name() + " 의 포함 브랜치 목록이 너무 많아 일부만 조회됨";
                        break;
                    }
                    branches = new LinkedHashSet<>();
                    for (GitLabClient.Ref r : refs.items()) {
                        if ("branch".equals(r.type())) branches.add(r.name());
                    }
                } catch (GitLabException e) {
                    stopReason = "태그 " + tag.name() + " 포함 브랜치 조회 실패: " + e.getMessage();
                    break;
                }
                looked.put(tag.commitSha(), branches);
            }
            for (String b : branches) {
                if (pending.remove(b)) result.put(b, new BranchTag(tag, BranchTag.State.FOUND));
            }
        }
        BranchTag.State rest = stopReason == null ? BranchTag.State.NONE : BranchTag.State.UNKNOWN;
        for (String b : pending) result.put(b, new BranchTag(null, rest));
        if (stopReason != null && !pending.isEmpty()) {
            notices.add(stopReason + " — " + pending.size() + "개 브랜치의 최근 Tag 는 '확인 불가'로 표시합니다.");
        }
        return result;
    }
}
