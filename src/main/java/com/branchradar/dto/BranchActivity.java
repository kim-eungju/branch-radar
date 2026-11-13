package com.branchradar.dto;

import java.time.LocalDate;

/**
 * 브랜치 활동 상태. 마지막 커밋 날짜(committed_date) 기준이며, 브랜치 생성일과는 다른 개념이다.
 * Flyway 상태와 독립적으로 표시한다.
 *
 * @param lastCommitDate 설정된 타임존 기준 마지막 커밋 날짜 (없으면 null)
 * @param days           경과 일수 (UNKNOWN 이면 null)
 */
public record BranchActivity(State state, LocalDate lastCommitDate, Long days) {

    public enum State {
        ACTIVE("활성", "plain"),
        STALE("미갱신", "neutral"),
        VERY_STALE("장기 미갱신", "danger"),
        UNKNOWN("확인 불가", "neutral");

        private final String label;
        private final String badgeClass;

        State(String label, String badgeClass) {
            this.label = label;
            this.badgeClass = badgeClass;
        }

        public String getLabel() {
            return label;
        }

        public String getBadgeClass() {
            return badgeClass;
        }
    }

    public static BranchActivity unknown(LocalDate lastCommitDate) {
        return new BranchActivity(State.UNKNOWN, lastCommitDate, null);
    }
}
