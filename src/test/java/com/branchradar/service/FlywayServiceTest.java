package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.BranchStatus;
import com.branchradar.dto.BranchStatus.Status;
import com.branchradar.dto.CompareResult.FlywayCompare;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.branchradar.TestData.FLYWAY;
import static com.branchradar.TestData.branch;
import static com.branchradar.TestData.failed;
import static org.assertj.core.api.Assertions.assertThat;

class FlywayServiceTest {

    private static Map<String, BranchStatus> byName(FlywayService.DashboardAnalysis a) {
        return a.rows().stream().collect(Collectors.toMap(r -> r.branch().name(), Function.identity()));
    }

    // ---------------------------------------------------------------- 파일명 해석

    @Test
    void 트리에서_버전_반복_무시_파일을_구분한다() {
        var set = FLYWAY.fromTree(List.of(
                new GitLabClient.TreeEntry("b1", "V2__b.sql", "blob", "db/migration/V2__b.sql"),
                new GitLabClient.TreeEntry("b2", "V10__c.sql", "blob", "db/migration/sub/V10__c.sql"),
                new GitLabClient.TreeEntry("b3", "R__view.sql", "blob", "db/migration/R__view.sql"),
                new GitLabClient.TreeEntry("b4", "README.md", "blob", "db/migration/README.md"),
                new GitLabClient.TreeEntry("b5", "Vbad.sql", "blob", "db/migration/Vbad.sql"),
                new GitLabClient.TreeEntry("b6", "sub", "tree", "db/migration/sub")));
        assertThat(set.versioned()).extracting(m -> m.fileName()).containsExactly("V2__b.sql", "V10__c.sql");
        assertThat(set.repeatable()).hasSize(1);
        assertThat(set.ignored()).hasSize(1).first().asString().startsWith("Vbad.sql");
    }

    // ---------------------------------------------------------------- 대시보드

    @Test
    void 전체_최신_Flyway는_버전으로_판정하고_없는_브랜치는_뒤처짐() {
        var develop = branch("develop", "V1__a.sql:a1", "V2__b.sql:b1");
        var feature = branch("feature/x", "V1__a.sql:a1", "V2__b.sql:b1", "V10__c.sql:c1");
        var release = branch("release/1.0", "V1__a.sql:a1");

        var result = FLYWAY.analyze(List.of(develop, feature, release), "develop");
        var rows = byName(result);

        assertThat(result.latest().version()).isEqualTo("10");
        assertThat(result.latest().conflict()).isFalse();
        assertThat(result.latestRefBranch()).isEqualTo("feature/x");
        assertThat(rows.get("feature/x").status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(rows.get("develop").status()).isEqualTo(Status.BEHIND);
        assertThat(rows.get("release/1.0").status()).isEqualTo(Status.BEHIND);
        assertThat(rows.get("release/1.0").missingVsLatestRef()).isEqualTo(2);
        assertThat(rows.get("release/1.0").missingVsBase()).isEqualTo(1);
        assertThat(rows.get("feature/x").extraVsBase()).isEqualTo(1);
        // 뒤처짐은 확정 오류처럼 표현하지 않는다
        assertThat(rows.get("release/1.0").message()).contains("의도적으로 이전 버전을 유지");
    }

    @Test
    void 동일_버전_동일_SQL이면_정상() {
        var a = branch("develop", "V20261105_143000__add_order_status.sql:same");
        var b = branch("feature/a", "V20261105_143000__add_order_status.sql:same");
        var rows = byName(FLYWAY.analyze(List.of(a, b), "develop"));
        assertThat(rows.get("develop").status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(rows.get("feature/a").status()).isEqualTo(Status.UP_TO_DATE);
    }

    @Test
    void 동일_버전_다른_SQL이면_충돌_기준브랜치에_없으면_양쪽_모두() {
        var develop = branch("develop", "V1__a.sql:a1");
        var a = branch("feature/a", "V1__a.sql:a1", "V20261105_143000__add_order_status.sql:x1");
        var b = branch("feature/b", "V1__a.sql:a1", "V20261105_143000__add_payment_status.sql:x2");

        var result = FLYWAY.analyze(List.of(develop, a, b), "develop");
        var rows = byName(result);

        assertThat(result.latest().conflict()).isTrue();
        assertThat(result.latest().variants()).hasSize(2);
        assertThat(rows.get("feature/a").status()).isEqualTo(Status.CONFLICT);
        assertThat(rows.get("feature/b").status()).isEqualTo(Status.CONFLICT);
        assertThat(rows.get("feature/a").conflictVersions()).containsExactly("20261105_143000");
        assertThat(rows.get("develop").status()).isEqualTo(Status.BEHIND);
    }

    @Test
    void 같은_파일명_다른_내용은_기준브랜치와_다른쪽만_충돌() {
        var develop = branch("develop", "V1__a.sql:a1", "V2__b.sql:b1");
        var main = branch("main", "V1__a.sql:a1", "V2__b.sql:b1");
        var stale = branch("feature/old", "V1__a.sql:EDITED", "V2__b.sql:b1");

        var rows = byName(FLYWAY.analyze(List.of(develop, main, stale), "develop"));
        assertThat(rows.get("develop").status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(rows.get("main").status()).isEqualTo(Status.UP_TO_DATE);
        assertThat(rows.get("feature/old").status()).isEqualTo(Status.CONFLICT);
        assertThat(rows.get("feature/old").conflictVersions()).containsExactly("1");
    }

    @Test
    void 최신_버전이_같아도_SQL이_다르면_정상이_아니다() {
        var develop = branch("develop", "V5__x.sql:c1");
        var other = branch("hotfix", "V5__x.sql:c2");
        var rows = byName(FLYWAY.analyze(List.of(develop, other), "develop"));
        assertThat(rows.get("hotfix").status()).isNotEqualTo(Status.UP_TO_DATE).isEqualTo(Status.CONFLICT);
    }

    @Test
    void 브랜치_내부_버전_중복은_Flyway_실행_오류로_구분() {
        var b = branch("develop", "V1__a.sql:a1", "V1.0__dup.sql:a2");
        var row = byName(FLYWAY.analyze(List.of(b), "develop")).get("develop");
        assertThat(row.status()).isEqualTo(Status.CONFLICT);
        assertThat(row.duplicateVersions()).hasSize(1);
        assertThat(row.message()).contains("Flyway 실행 시");
    }

    @Test
    void Flyway_파일이_없거나_조회불가인_브랜치는_별도_상태() {
        var develop = branch("develop", "V1__a.sql:a1");
        var empty = branch("docs");
        var broken = failed("broken");
        var rows = byName(FLYWAY.analyze(List.of(develop, empty, broken), "develop"));
        assertThat(rows.get("docs").status()).isEqualTo(Status.NO_FILES);
        assertThat(rows.get("broken").status()).isEqualTo(Status.ERROR);
        assertThat(rows.get("develop").status()).isEqualTo(Status.UP_TO_DATE);
    }

    @Test
    void 모든_브랜치에_파일이_없으면_최신_없음() {
        var result = FLYWAY.analyze(List.of(branch("a"), branch("b")), "a");
        assertThat(result.latest()).isNull();
        assertThat(result.rows()).allMatch(r -> r.status() == Status.NO_FILES);
    }

    @Test
    void Repeatable은_버전_충돌로_판정하지_않는다() {
        var a = branch("develop", "V1__a.sql:a1", "R__view.sql:r1");
        var b = branch("feature", "V1__a.sql:a1", "R__view.sql:r2");
        var rows = byName(FLYWAY.analyze(List.of(a, b), "develop"));
        assertThat(rows.get("feature").status()).isEqualTo(Status.UP_TO_DATE);
        FlywayCompare cmp = FLYWAY.compare(a, b);
        assertThat(cmp.conflicts()).isEmpty();
        assertThat(cmp.repeatableDiffs()).hasSize(1);
    }

    // ---------------------------------------------------------------- A/B 비교

    @Test
    void A에_비해_B에_없는_Flyway() {
        BranchSnapshot a = branch("develop", "V1__a.sql:a1", "V2__b.sql:b1", "V3__c.sql:c1");
        BranchSnapshot b = branch("release", "V1__a.sql:a1", "V4__d.sql:d1");

        FlywayCompare cmp = FLYWAY.compare(a, b);

        assertThat(cmp.onlyInA()).extracting(i -> i.migration().fileName()).containsExactly("V2__b.sql", "V3__c.sql");
        assertThat(cmp.onlyInB()).extracting(i -> i.migration().fileName()).containsExactly("V4__d.sql");
        assertThat(cmp.same()).extracting(i -> i.migration().fileName()).containsExactly("V1__a.sql");
        // B 최신(V4)보다 낮은 버전은 outOfOrder 안내
        assertThat(cmp.onlyInA().get(0).note()).contains("outOfOrder");
    }

    @Test
    void 비교는_버전뿐_아니라_파일명과_내용도_본다() {
        BranchSnapshot a = branch("A", "V1__add_order_status.sql:x1", "V2__same_name.sql:y1", "V3__renamed.sql:z1");
        BranchSnapshot b = branch("B", "V1__add_payment_status.sql:x2", "V2__same_name.sql:y2", "V3__other_name.sql:z1");

        FlywayCompare cmp = FLYWAY.compare(a, b);

        assertThat(cmp.onlyInA()).isEmpty();
        assertThat(cmp.same()).isEmpty();
        assertThat(cmp.conflicts()).hasSize(3);
        assertThat(cmp.conflicts().get(0).reason()).contains("파일명·SQL 모두 다름");
        assertThat(cmp.conflicts().get(1).reason()).contains("같은 파일명이지만 SQL 내용이 다름");
        assertThat(cmp.conflicts().get(2).reason()).contains("파일명(설명)만 다름");
    }

    @Test
    void 같은_내용이_다른_버전으로_있으면_안내() {
        BranchSnapshot a = branch("A", "V5__x.sql:same");
        BranchSnapshot b = branch("B", "V6__x.sql:same");
        FlywayCompare cmp = FLYWAY.compare(a, b);
        assertThat(cmp.onlyInA().get(0).note()).contains("V6__x.sql");
    }

    // ---------------------------------------------------------------- checksum

    @Test
    void Flyway_checksum은_줄바꿈과_BOM_차이를_무시한다() {
        byte[] lf = "CREATE TABLE a (id int);\nINSERT INTO a VALUES (1);\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "﻿CREATE TABLE a (id int);\r\nINSERT INTO a VALUES (1);\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] other = "CREATE TABLE a (id bigint);\n".getBytes(StandardCharsets.UTF_8);
        assertThat(FlywayService.flywayChecksum(lf)).isEqualTo(FlywayService.flywayChecksum(crlf));
        assertThat(FlywayService.flywayChecksum(lf)).isNotEqualTo(FlywayService.flywayChecksum(other));
    }
}
