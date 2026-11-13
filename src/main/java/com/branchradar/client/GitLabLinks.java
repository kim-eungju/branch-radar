package com.branchradar.client;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * GitLab 웹 페이지 링크. 템플릿에서 ${links.branch(...)} 형태로 사용한다 (ViewModelAdvice).
 * 브랜치명/파일 경로는 '/' 를 기준으로 나눠 각 조각을 퍼센트 인코딩한다(#, %, 공백, 한글 등 안전).
 */
@Component
public class GitLabLinks {

    public String branch(String projectWebUrl, String branch) {
        return trim(projectWebUrl) + "/-/tree/" + encodePath(branch);
    }

    public String commit(String projectWebUrl, String sha) {
        return trim(projectWebUrl) + "/-/commit/" + encodePath(sha);
    }

    /** 분석한 시점과 같은 내용을 보도록 브랜치명 대신 커밋 SHA 로 링크한다. */
    public String file(String projectWebUrl, String ref, String path) {
        return trim(projectWebUrl) + "/-/blob/" + encodePath(ref) + "/" + encodePath(path);
    }

    public String tag(String projectWebUrl, String tag) {
        return trim(projectWebUrl) + "/-/tags/" + encodePath(tag);
    }

    public String compare(String projectWebUrl, String from, String to) {
        return trim(projectWebUrl) + "/-/compare/" + encodePath(from) + "..." + encodePath(to) + "?straight=false";
    }

    static String encodePath(String value) {
        if (value == null) return "";
        String[] segments = value.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(UriUtils.encodePathSegment(segments[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String trim(String url) {
        if (url == null) return "";
        String u = url;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
