package com.branchradar.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 한 브랜치의 최신 커밋 시점 Flyway 파일 목록.
 *
 * @param versioned    버전 마이그레이션 (버전 오름차순)
 * @param repeatable   R__ 마이그레이션
 * @param ignoredFiles 디렉터리 안의 파일 중 Flyway 이름 규칙에 맞지 않아 제외한 파일
 * @param error        조회 실패 시 메시지 (null 이면 정상)
 */
public record BranchSnapshot(
        String name,
        String commitSha,
        OffsetDateTime commitDate,
        String commitTitle,
        List<FlywayMigration> versioned,
        List<FlywayMigration> repeatable,
        List<String> ignoredFiles,
        boolean dirMissing,
        String error) {

    public boolean ok() {
        return error == null;
    }

    public FlywayMigration latest() {
        return versioned == null || versioned.isEmpty() ? null : versioned.get(versioned.size() - 1);
    }

    public String shortSha() {
        return commitSha == null ? "" : commitSha.substring(0, Math.min(8, commitSha.length()));
    }
}
