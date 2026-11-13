package com.branchradar.dto;

/**
 * 저장소에서 발견한 Flyway SQL 파일 1개.
 *
 * @param version  버전 마이그레이션이면 버전, Repeatable 이면 null
 * @param blobId   Git blob SHA-1. 파일 내용(바이트)이 같으면 같은 값이다.
 *                 Flyway 의 checksum(CRC32) 과는 다른 값이다.
 */
public record FlywayMigration(
        Type type,
        FlywayVersion version,
        String description,
        String fileName,
        String path,
        String blobId) {

    public enum Type { VERSIONED, REPEATABLE }

    public boolean isRepeatable() {
        return type == Type.REPEATABLE;
    }

    /** 파일명 + 내용이 모두 같은지 판단하는 식별자 */
    public String variantId() {
        return fileName + "|" + blobId;
    }

    public boolean sameFileAndContent(FlywayMigration o) {
        return o != null && fileName.equals(o.fileName) && blobId.equals(o.blobId);
    }

    public String versionLabel() {
        return version == null ? "R" : version.getRaw();
    }

    public String shortBlob() {
        return blobId == null ? "" : blobId.substring(0, Math.min(8, blobId.length()));
    }
}
