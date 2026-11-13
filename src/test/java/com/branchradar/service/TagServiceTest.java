package com.branchradar.service;

import com.branchradar.TestData;
import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.TagInfo;
import com.branchradar.dto.TagSummary.BranchTag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TagServiceTest {

    private static TagService service(GitLabProperties.TagSort sort, String pattern) {
        GitLabProperties p = TestData.props();
        GitLabProperties props = new GitLabProperties(p.gitlab(), p.flyway(), p.branch(),
                new GitLabProperties.Tag(true, sort, pattern, 50), p.staleBranch());
        return new TagService(props, null);
    }

    private static TagInfo tag(TagService s, String name, String sha) {
        return new TagInfo(name, s.extractVersion(name), sha, null, null, false);
    }

    @Test
    void 버전_비교는_숫자와_사전릴리스_규칙을_따른다() {
        assertThat(TagService.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(TagService.compareVersions("2", "1.99.99")).isPositive();
        assertThat(TagService.compareVersions("1.0.0", "1.0.0-rc.1")).isPositive();
        assertThat(TagService.compareVersions("1.0.0-rc.10", "1.0.0-rc.2")).isPositive();
        assertThat(TagService.compareVersions("1.0", "1.0.0")).isZero();
        assertThat(TagService.compareVersions("1.0.0+build.5", "1.0.0")).isZero();
    }

    @Test
    void semver_정책은_태그명_속_버전으로_정렬한다() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, null);
        List<TagInfo> tags = new ArrayList<>(List.of(
                tag(s, "v1.9.0", "a"), tag(s, "v1.10.0", "b"), tag(s, "nightly", "c"), tag(s, "v1.10.0-rc.1", "d")));
        tags.sort(s.latestFirst());
        assertThat(tags).extracting(TagInfo::name).containsExactly("v1.10.0", "v1.10.0-rc.1", "v1.9.0", "nightly");
    }

    @Test
    void 사용자_정의_버전_패턴() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, "^app-(\\d+\\.\\d+\\.\\d+)$");
        assertThat(s.extractVersion("app-1.2.3")).isEqualTo("1.2.3");
        assertThat(s.extractVersion("lib-1.2.3")).isNull();
    }

    @Test
    void created_정책에서_lightweight_태그는_뒤로() {
        TagService s = service(GitLabProperties.TagSort.CREATED, null);
        TagInfo older = new TagInfo("a", null, "1", null, OffsetDateTime.parse("2026-01-01T00:00:00Z"), true);
        TagInfo newer = new TagInfo("b", null, "2", null, OffsetDateTime.parse("2026-06-01T00:00:00Z"), true);
        TagInfo light = new TagInfo("c", null, "3", null, null, false);
        List<TagInfo> tags = new ArrayList<>(List.of(light, older, newer));
        tags.sort(s.latestFirst());
        assertThat(tags).extracting(TagInfo::name).containsExactly("b", "a", "c");
    }

    @Test
    void annotated와_lightweight_태그_모두_실제_커밋으로_판정() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, null);
        var commit = new GitLabClient.Commit("c0ffee", "c0ffee", "t", "m", null, null, null, null, null, null);
        TagInfo annotated = s.toInfo(new GitLabClient.Tag("v1.0.0", "release", "7a6000", commit, OffsetDateTime.now()));
        TagInfo lightweight = s.toInfo(new GitLabClient.Tag("v1.0.1", null, "c0ffee", commit, null));
        assertThat(annotated.annotated()).isTrue();
        assertThat(annotated.commitSha()).isEqualTo("c0ffee");
        assertThat(lightweight.annotated()).isFalse();
        assertThat(lightweight.commitSha()).isEqualTo("c0ffee");
    }

    private static GitLabClient.Paged<GitLabClient.Ref> refs(String... branches) {
        return new GitLabClient.Paged<>(java.util.Arrays.stream(branches)
                .map(b -> new GitLabClient.Ref("branch", b)).toList(), true);
    }

    @Test
    void 브랜치별_최근_태그는_조상_커밋_포함_여부로_판정() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, null);
        List<TagInfo> tags = List.of(tag(s, "v2.0.0", "c2"), tag(s, "v1.0.0", "c1"));
        AtomicInteger calls = new AtomicInteger();
        Function<String, GitLabClient.Paged<GitLabClient.Ref>> lookup = sha -> {
            calls.incrementAndGet();
            return sha.equals("c2") ? refs("develop", "main") : refs("develop", "main", "release/1");
        };

        Map<String, BranchTag> result = TagService.assign(tags, List.of("develop", "main", "release/1", "orphan"),
                lookup, 50, new ArrayList<>());

        assertThat(result.get("develop").tag().name()).isEqualTo("v2.0.0");
        assertThat(result.get("release/1").tag().name()).isEqualTo("v1.0.0");
        assertThat(result.get("orphan").state()).isEqualTo(BranchTag.State.NONE);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 판정이_끝나면_더_조회하지_않는다() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, null);
        List<TagInfo> tags = List.of(tag(s, "v3", "c3"), tag(s, "v2", "c2"), tag(s, "v1", "c1"));
        AtomicInteger calls = new AtomicInteger();
        TagService.assign(tags, List.of("main"), sha -> {
            calls.incrementAndGet();
            return refs("main");
        }, 50, new ArrayList<>());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 조회_상한이나_오류면_추측하지_않고_확인불가() {
        TagService s = service(GitLabProperties.TagSort.SEMVER, null);
        List<TagInfo> tags = List.of(tag(s, "v2", "c2"), tag(s, "v1", "c1"));
        List<String> notices = new ArrayList<>();

        Map<String, BranchTag> limited = TagService.assign(tags, List.of("main", "old"),
                sha -> refs("main"), 1, notices);
        assertThat(limited.get("main").state()).isEqualTo(BranchTag.State.FOUND);
        assertThat(limited.get("old").state()).isEqualTo(BranchTag.State.UNKNOWN);
        assertThat(notices).hasSize(1);

        Map<String, BranchTag> failed = TagService.assign(tags, List.of("main"),
                sha -> { throw new GitLabException(500, "boom", null); }, 50, new ArrayList<>());
        assertThat(failed.get("main").state()).isEqualTo(BranchTag.State.UNKNOWN);
    }
}
