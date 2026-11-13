package com.branchradar.service;

import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchActivity;
import com.branchradar.dto.BranchActivity.State;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 마지막 커밋 날짜로 브랜치 활동 상태를 판정한다. GitLab 을 호출하지 않는다.
 * 현재 시각은 주입된 Clock 을 쓰므로 테스트에서 날짜를 고정할 수 있다.
 */
@Service
public class BranchActivityService {

    private final GitLabProperties.StaleBranch cfg;
    private final ZoneId zone;
    private final Clock clock;

    public BranchActivityService(GitLabProperties properties, Clock clock) {
        this.cfg = properties.staleBranch();
        this.zone = cfg.zoneId();
        this.clock = clock;
    }

    public int warningDays() {
        return cfg.warningDays();
    }

    public int dangerDays() {
        return cfg.dangerDays();
    }

    /**
     * 경과 일수 = (설정 타임존 기준 오늘 날짜) - (설정 타임존 기준 커밋 날짜).
     * 시각이 아니라 날짜 차이이므로, 서울 기준 어제 23:59 커밋은 오늘 00:01 에 1일 경과다.
     */
    public BranchActivity of(OffsetDateTime committedDate) {
        if (committedDate == null) {
            return BranchActivity.unknown(null);
        }
        LocalDate commitDay = committedDate.atZoneSameInstant(zone).toLocalDate();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        long days = ChronoUnit.DAYS.between(commitDay, today);
        if (days < 0) {
            // 미래 날짜(커미터 PC 시계 오류 등): 음수 일수를 보여주지 않는다
            return BranchActivity.unknown(commitDay);
        }
        State state = days >= cfg.dangerDays() ? State.VERY_STALE
                : days >= cfg.warningDays() ? State.STALE
                : State.ACTIVE;
        return new BranchActivity(state, commitDay, days);
    }
}
