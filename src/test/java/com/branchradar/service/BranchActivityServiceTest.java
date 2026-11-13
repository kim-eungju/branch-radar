package com.branchradar.service;

import com.branchradar.TestData;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchActivity;
import com.branchradar.dto.BranchActivity.State;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchActivityServiceTest {

    /** 지금 = 2026-11-05 12:00 (Asia/Seoul) */
    static final Clock NOW = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC);

    private static BranchActivityService service(Clock clock, String zone) {
        GitLabProperties p = TestData.props();
        return new BranchActivityService(new GitLabProperties(p.gitlab(), p.flyway(), p.branch(), p.tag(),
                new GitLabProperties.StaleBranch(30, 60, zone)), clock);
    }

    private static final BranchActivityService SEOUL = service(NOW, "Asia/Seoul");

    private static OffsetDateTime seoulDaysAgo(int days) {
        return OffsetDateTime.parse("2026-11-05T09:30:00+09:00").minusDays(days);
    }

    @Test
    void 오늘_커밋은_0일_활성() {
        BranchActivity a = SEOUL.of(seoulDaysAgo(0));
        assertThat(a.days()).isZero();
        assertThat(a.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void 일수_경계값() {
        assertThat(SEOUL.of(seoulDaysAgo(29)).state()).isEqualTo(State.ACTIVE);
        assertThat(SEOUL.of(seoulDaysAgo(30)).state()).isEqualTo(State.STALE);
        assertThat(SEOUL.of(seoulDaysAgo(59)).state()).isEqualTo(State.STALE);
        assertThat(SEOUL.of(seoulDaysAgo(60)).state()).isEqualTo(State.VERY_STALE);
        BranchActivity old = SEOUL.of(seoulDaysAgo(150));
        assertThat(old.state()).isEqualTo(State.VERY_STALE);
        assertThat(old.days()).isEqualTo(150);
    }

    @Test
    void 날짜가_없으면_UNKNOWN() {
        BranchActivity a = SEOUL.of(null);
        assertThat(a.state()).isEqualTo(State.UNKNOWN);
        assertThat(a.days()).isNull();
    }

    @Test
    void 미래_날짜는_음수_대신_UNKNOWN() {
        BranchActivity a = SEOUL.of(seoulDaysAgo(-3));
        assertThat(a.state()).isEqualTo(State.UNKNOWN);
        assertThat(a.days()).isNull();
    }

    @Test
    void 시각이_아니라_설정_타임존의_날짜_차이로_계산한다() {
        // 지금: 2026-11-05 00:10 서울 (= 11-04 15:10 UTC)
        Clock justAfterMidnightSeoul = Clock.fixed(Instant.parse("2026-11-04T15:10:00Z"), ZoneOffset.UTC);
        // 커밋: 2026-11-04 23:50 서울 (20분 전)
        OffsetDateTime commit = OffsetDateTime.parse("2026-11-04T14:50:00Z");

        // 서울 기준으로는 날짜가 바뀌었으므로 1일
        assertThat(service(justAfterMidnightSeoul, "Asia/Seoul").of(commit).days()).isEqualTo(1);
        // UTC 기준으로는 같은 날(11-04)이므로 0일
        assertThat(service(justAfterMidnightSeoul, "UTC").of(commit).days()).isZero();
    }

    @Test
    void 커밋_시각의_오프셋과_무관하게_설정_타임존으로_변환한다() {
        // 2026-10-06 20:00 -05:00 = 2026-10-07 10:00 서울 → 29일 전
        OffsetDateTime commit = OffsetDateTime.parse("2026-10-06T20:00:00-05:00");
        assertThat(SEOUL.of(commit).days()).isEqualTo(29);
        assertThat(SEOUL.of(commit).lastCommitDate()).hasToString("2026-10-07");
    }

    @Test
    void 잘못된_설정값은_거부한다() {
        assertThatThrownBy(() -> new GitLabProperties.StaleBranch(0, 60, "Asia/Seoul"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("warning-days");
        assertThatThrownBy(() -> new GitLabProperties.StaleBranch(30, 30, "Asia/Seoul"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("danger-days");
        assertThatThrownBy(() -> new GitLabProperties.StaleBranch(30, 10, "Asia/Seoul"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitLabProperties.StaleBranch(30, 60, "Mars/Base"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timezone");
    }
}
