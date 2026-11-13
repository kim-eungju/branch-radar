package com.branchradar.dto;

import java.time.OffsetDateTime;

/**
 * GitLab 태그 1개. 세 가지 날짜/버전 개념을 구분해서 담는다.
 *
 * @param version    태그 이름에서 추출한 패키지 버전 (추출 불가면 null)
 * @param commitSha  태그가 가리키는 커밋 (annotated 태그도 커밋으로 역참조한 값)
 * @param commitDate 그 커밋의 커밋 날짜 — 태그를 만든 날짜가 아님
 * @param createdAt  annotated 태그 생성일. lightweight 태그는 생성일 정보가 없어 null
 */
public record TagInfo(
        String name,
        String version,
        String commitSha,
        OffsetDateTime commitDate,
        OffsetDateTime createdAt,
        boolean annotated) {

    public String shortSha() {
        return commitSha == null ? "" : commitSha.substring(0, Math.min(8, commitSha.length()));
    }
}
