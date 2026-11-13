package com.branchradar.dto;

import java.util.List;
import java.util.Map;

/**
 * 대시보드용 태그 요약. Flyway 상태와는 독립적이다.
 *
 * @param latest   정렬 정책상 전체 최신 태그 (태그가 없거나 목록이 불완전하면 null)
 * @param byBranch 브랜치명 → 그 브랜치 이력에 포함된 가장 최신 태그
 * @param error    태그 조회 자체가 실패한 경우 메시지
 */
public record TagSummary(
        boolean enabled,
        TagInfo latest,
        int totalTags,
        String sortPolicy,
        Map<String, BranchTag> byBranch,
        String error,
        List<String> notices) {

    public static TagSummary disabled() {
        return new TagSummary(false, null, 0, "", Map.of(), null, List.of());
    }

    public BranchTag forBranch(String branch) {
        return byBranch.getOrDefault(branch, new BranchTag(null, BranchTag.State.UNKNOWN));
    }

    public record BranchTag(TagInfo tag, State state) {
        public enum State {
            /** 포함된 태그 확인됨 */
            FOUND,
            /** 모든 태그를 확인했지만 포함된 태그 없음 */
            NONE,
            /** 조회 상한/오류/불완전 목록 때문에 판정하지 않음 */
            UNKNOWN
        }
    }
}
