package com.branchradar;

import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.FlywayMigration;
import com.branchradar.service.FlywayService;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 테스트용 설정과 브랜치 데이터 생성기 */
public final class TestData {

    public static final String BASE_URL = "https://gitlab.example.com";

    private TestData() {
    }

    public static GitLabProperties props() {
        return props(3);
    }

    public static GitLabProperties props(int maxPages) {
        return new GitLabProperties(
                new GitLabProperties.Gitlab(BASE_URL, "group/my project", "test-token",
                        Duration.ofSeconds(2), Duration.ofSeconds(2), maxPages),
                new GitLabProperties.Flyway("db/migration", "V", "R", "__", List.of(".sql"), true,
                        "build/tmp/flyway-files-test", false, "VADA"),
                new GitLabProperties.Branch("develop", 2),
                new GitLabProperties.Tag(true, GitLabProperties.TagSort.SEMVER, null, 50),
                new GitLabProperties.StaleBranch(30, 60, "Asia/Seoul"));
    }

    public static final FlywayService FLYWAY = new FlywayService(props(), null);

    /** "V1__a.sql:blob1" 형식의 파일 목록으로 브랜치 스냅샷 생성 */
    public static BranchSnapshot branch(String name, String... files) {
        List<FlywayMigration> versioned = new ArrayList<>();
        List<FlywayMigration> repeatable = new ArrayList<>();
        for (String f : files) {
            String[] p = f.split(":");
            FlywayMigration m = FLYWAY.parse("db/migration/" + p[0], p[1]);
            (m.isRepeatable() ? repeatable : versioned).add(m);
        }
        versioned.sort(FlywayService.BY_VERSION);
        return new BranchSnapshot(name, "sha-" + name, OffsetDateTime.parse("2026-11-01T10:00:00+09:00"),
                "commit of " + name, versioned, repeatable, List.of(), false, null);
    }

    public static BranchSnapshot failed(String name) {
        return new BranchSnapshot(name, "sha-" + name, null, null, List.of(), List.of(), List.of(), false, "HTTP 500");
    }
}
