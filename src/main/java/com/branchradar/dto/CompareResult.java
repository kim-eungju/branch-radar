package com.branchradar.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A/B 브랜치 비교 결과. "A 브랜치에 비해 B 브랜치에 없는 항목" 이 핵심이다.
 */
public record CompareResult(
        BranchSnapshot a,
        BranchSnapshot b,
        FlywayCompare flyway,
        CommitCompare commits) {

    public record FlywayCompare(
            List<Item> onlyInA,
            List<Item> onlyInB,
            List<Item> same,
            List<Conflict> conflicts,
            List<String> duplicatesInA,
            List<String> duplicatesInB,
            List<RepeatableDiff> repeatableDiffs) {
    }

    /** 한쪽에만 있는 (또는 양쪽 동일한) 마이그레이션 */
    public record Item(FlywayMigration migration, String note) {
    }

    /**
     * 같은 버전인데 파일명 또는 내용이 다른 경우.
     * checksumA/B 는 원문으로 계산한 Flyway 방식 CRC32 (계산하지 못했으면 null).
     */
    public record Conflict(
            String version,
            FlywayMigration a,
            FlywayMigration b,
            String reason,
            Integer checksumA,
            Integer checksumB,
            String checksumNote) {

        public Conflict withChecksums(Integer ca, Integer cb, String note) {
            return new Conflict(version, a, b, reason, ca, cb, note);
        }
    }

    /** Repeatable(R__) 차이. 버전 충돌이 아니라 참고 정보다. */
    public record RepeatableDiff(String fileName, FlywayMigration a, FlywayMigration b, String kind) {
    }

    /**
     * @param aNotB      A 에서 도달 가능하지만 B 에서는 도달 불가능한 커밋 (Compare from=B to=A)
     * @param bNotA      B 에서 도달 가능하지만 A 에서는 도달 불가능한 커밋 (Compare from=A to=B)
     * @param incomplete GitLab 이 타임아웃 등으로 결과가 불완전하다고 알린 경우
     */
    public record CommitCompare(
            List<CommitItem> aNotB,
            List<CommitItem> bNotA,
            boolean incomplete,
            String error,
            String gitlabCompareUrl) {

        /** A 에만 있는 커밋 중 cherry-pick 흔적이 없는 것 — 실제 누락 가능성이 높아 우선 확인 대상 */
        public List<CommitItem> aNotBNoPick() {
            return aNotB.stream().filter(i -> !i.cherryPickSuspect()).toList();
        }

        /** A 에만 있는 커밋 중 cherry-pick 흔적(힌트)이 있는 것 — 이미 반영되었을 수 있음 */
        public List<CommitItem> aNotBMaybePicked() {
            return aNotB.stream().filter(CommitItem::cherryPickSuspect).toList();
        }

        public List<CommitItem> bNotANoPick() {
            return bNotA.stream().filter(i -> !i.cherryPickSuspect()).toList();
        }

        public List<CommitItem> bNotAMaybePicked() {
            return bNotA.stream().filter(CommitItem::cherryPickSuspect).toList();
        }

        /** 작성자 필터용. 양쪽 목록의 작성자를 중복 없이 이름순으로 */
        public List<String> authors() {
            return java.util.stream.Stream.concat(aNotB.stream(), bNotA.stream())
                    .map(CommitItem::author)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public record CommitItem(
            String sha,
            String shortSha,
            String title,
            String author,
            OffsetDateTime date,
            String webUrl,
            boolean merge,
            String hint) {

        /** cherry-pick 기록(-x) 또는 제목·작성자 일치 힌트가 붙은 커밋 */
        public boolean cherryPickSuspect() {
            return hint != null;
        }
    }
}
