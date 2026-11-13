package com.branchradar.dto;

import java.util.List;
import java.util.Map;

/**
 * 대시보드 화면 모델.
 *
 * @param latest          전체 브랜치 중 가장 최신 Flyway (없으면 null)
 * @param latestRefBranch 최신 Flyway 를 가진 대표 브랜치 (기준 브랜치가 갖고 있으면 기준 브랜치)
 * @param notices         결과가 완전하지 않은 이유 등 사용자에게 알릴 내용
 */
public record DashboardView(
        String projectName,
        String projectWebUrl,
        String baseBranch,
        boolean baseFound,
        List<String> branchNames,
        List<BranchStatus> rows,
        LatestInfo latest,
        String latestRefBranch,
        TagSummary tags,
        Map<String, BranchActivity> activities,
        int staleWarningDays,
        int staleDangerDays,
        List<String> notices) {

    /** 브랜치 활동 상태 (Flyway 상태와 독립) */
    public BranchActivity activity(String branch) {
        return activities.getOrDefault(branch, BranchActivity.unknown(null));
    }

    /** @param state BranchActivity.State 이름 */
    public long activityCount(String state) {
        BranchActivity.State s = BranchActivity.State.valueOf(state);
        return activities.values().stream().filter(a -> a.state() == s).count();
    }

    public record LatestInfo(String version, boolean conflict, List<Variant> variants) {
    }

    /** 최신 버전의 파일 변형 (파일명+내용이 같으면 하나의 변형) */
    public record Variant(FlywayMigration migration, String ref, List<String> branches) {
    }

    /** @param status BranchStatus.Status 이름 (템플릿에서 문자열로 호출) */
    public long count(String status) {
        BranchStatus.Status s = BranchStatus.Status.valueOf(status);
        return rows.stream().filter(r -> r.status() == s).count();
    }

    public long warningCount() {
        return rows.stream().filter(BranchStatus::isWarning).count();
    }
}
