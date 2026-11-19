package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.config.GitLabProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 브랜치 커밋 검색. GitLab 커밋 목록 API 는 메시지 검색을 지원하지 않으므로
 * 최신 커밋부터 페이지(100개)씩 읽으며 메시지·작성자·SHA 를 직접 비교한다.
 * 결과가 {@link #MAX_RESULTS} 개 모이거나 max-pages 에 닿으면 멈춘다.
 */
@Service
public class CommitSearchService {

    static final int MAX_RESULTS = 100;
    private static final int PAGE_SIZE = 100;

    private final GitLabClient client;
    private final GitLabProperties properties;

    public CommitSearchService(GitLabClient client, GitLabProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param scanned   검색한 커밋 수
     * @param truncated true 면 끝까지 보지 못했다 (결과 상한 또는 max-pages 도달)
     */
    public record Result(List<GitLabClient.Commit> items, int scanned, boolean truncated) {

        /** 결과 상한에 닿아 멈췄는지 (아니면 max-pages 에 닿은 것) */
        public boolean limitReached() {
            return items.size() >= MAX_RESULTS;
        }

        public int limit() {
            return MAX_RESULTS;
        }
    }

    public Result search(String branch, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<GitLabClient.Commit> found = new ArrayList<>();
        int scanned = 0;
        int maxPages = Math.max(1, properties.gitlab().maxPages());
        for (int page = 1; page <= maxPages; page++) {
            GitLabClient.CommitPage res = client.listCommits(branch, page, PAGE_SIZE);
            for (GitLabClient.Commit c : res.items()) {
                scanned++;
                if (matches(c, q)) {
                    found.add(c);
                    if (found.size() >= MAX_RESULTS) {
                        return new Result(found, scanned, true);
                    }
                }
            }
            if (!res.hasNext()) {
                return new Result(found, scanned, false);
            }
        }
        return new Result(found, scanned, true);
    }

    static boolean matches(GitLabClient.Commit c, String q) {
        return contains(c.message() != null ? c.message() : c.title(), q)
                || contains(c.authorName(), q)
                || contains(c.authorEmail(), q)
                || (c.id() != null && c.id().toLowerCase(Locale.ROOT).startsWith(q));
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }
}
