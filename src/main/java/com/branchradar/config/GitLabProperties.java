package com.branchradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * application.yml 의 branch-radar.* 설정.
 */
@ConfigurationProperties(prefix = "branch-radar")
public record GitLabProperties(Gitlab gitlab, Flyway flyway, Branch branch, Tag tag, StaleBranch staleBranch) {

    public GitLabProperties {
        if (tag == null) tag = new Tag(true, TagSort.SEMVER, null, 50);
        if (staleBranch == null) staleBranch = new StaleBranch(30, 60, "Asia/Seoul");
    }

    /**
     * 장기 미갱신 브랜치 기준. 잘못된 값이면 애플리케이션 시작 시 실패한다.
     *
     * @param warningDays 이 일수 이상 커밋이 없으면 미갱신(STALE)
     * @param dangerDays  이 일수 이상 커밋이 없으면 장기 미갱신(VERY_STALE)
     * @param timezone    경과 일수를 계산할 날짜 기준 타임존
     */
    public record StaleBranch(
            @DefaultValue("30") int warningDays,
            @DefaultValue("60") int dangerDays,
            @DefaultValue("Asia/Seoul") String timezone) {

        public StaleBranch {
            if (warningDays < 1) {
                throw new IllegalArgumentException("branch-radar.stale-branch.warning-days 는 1 이상이어야 합니다: " + warningDays);
            }
            if (dangerDays <= warningDays) {
                throw new IllegalArgumentException("branch-radar.stale-branch.danger-days(" + dangerDays
                        + ") 는 warning-days(" + warningDays + ") 보다 커야 합니다.");
            }
            try {
                java.time.ZoneId.of(timezone);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("branch-radar.stale-branch.timezone 이 올바르지 않습니다: " + timezone, e);
            }
        }

        public java.time.ZoneId zoneId() {
            return java.time.ZoneId.of(timezone);
        }
    }

    /** 태그 정렬 정책 */
    public enum TagSort {
        /** 태그 이름에서 버전(1.2.3, 1.2.3-rc.1)을 추출해 숫자 순서로 비교. 버전이 없는 태그는 가장 뒤 */
        SEMVER,
        /** annotated 태그 생성일 순. lightweight 태그는 생성일이 없어 가장 뒤 */
        CREATED,
        /** 이름 문자열 순 */
        NAME
    }

    /**
     * @param versionPattern 태그 이름에서 버전을 뽑을 정규식 (첫 번째 그룹 사용). 비우면 기본 규칙
     * @param maxLookups     브랜치 포함 판정을 위한 커밋별 API 조회 최대 횟수
     */
    public record Tag(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("semver") TagSort sort,
            String versionPattern,
            @DefaultValue("50") int maxLookups) {
    }

    public record Gitlab(
            String baseUrl,
            String projectId,
            String token,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("50") int maxPages) {

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * @param localDir           기준 브랜치의 Flyway 파일과 작성 중인 임시저장을 보관하는 로컬 폴더 (실행 위치 기준 상대 경로 가능)
     * @param syncOnStartup      시작할 때 기준 브랜치의 Flyway 파일을 localDir 로 내려받을지
     * @param newFileDescription Flyway 관리 화면에서 새로 만드는 파일의 설명 부분 (V261110.001__VADA.sql 의 VADA)
     */
    public record Flyway(
            @DefaultValue("src/main/resources/db/migration") String path,
            @DefaultValue("V") String versionedPrefix,
            @DefaultValue("R") String repeatablePrefix,
            @DefaultValue("__") String separator,
            @DefaultValue(".sql") List<String> suffixes,
            @DefaultValue("true") boolean dateAwareOrdering,
            @DefaultValue("flyway-files") String localDir,
            @DefaultValue("true") boolean syncOnStartup,
            @DefaultValue("VADA") String newFileDescription) {

        /** 앞뒤 슬래시를 제거한 디렉터리 경로 */
        public String normalizedPath() {
            String p = path == null ? "" : path.trim();
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        }
    }

    public record Branch(
            @DefaultValue("develop") String base,
            @DefaultValue("6") int fetchConcurrency) {
    }
}
