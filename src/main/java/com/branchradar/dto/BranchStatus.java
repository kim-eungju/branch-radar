package com.branchradar.dto;

import java.util.List;

/**
 * 대시보드 한 줄.
 *
 * @param conflictVersions   다른 브랜치에 같은 버전 · 다른 파일(이름 또는 내용)이 있는 버전
 * @param duplicateVersions  이 브랜치 안에서 같은 버전이 2개 이상 → Flyway 실행 시 실제 오류
 * @param missingVsBase      기준 브랜치에는 있고 이 브랜치에는 없는 버전 수 (기준 브랜치 조회 불가 시 null)
 * @param extraVsBase        이 브랜치에만 있고 기준 브랜치에는 없는 버전 수
 * @param missingVsLatestRef 최신 Flyway 보유 기준 브랜치 대비 누락 버전 수
 */
public record BranchStatus(
        BranchSnapshot branch,
        Status status,
        FlywayMigration latest,
        List<String> conflictVersions,
        List<String> duplicateVersions,
        Integer missingVsBase,
        Integer extraVsBase,
        int missingVsLatestRef,
        boolean base,
        String message) {

    public enum Status {
        UP_TO_DATE("최신", "ok"),
        BEHIND("뒤처짐", "warn"),
        CONFLICT("충돌 위험", "danger"),
        NO_FILES("파일 없음", "neutral"),
        ERROR("조회 불가", "neutral");

        private final String label;
        private final String color;

        Status(String label, String color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() {
            return label;
        }

        public String getColor() {
            return color;
        }
    }

    public boolean isWarning() {
        return status == Status.BEHIND || status == Status.CONFLICT;
    }
}
