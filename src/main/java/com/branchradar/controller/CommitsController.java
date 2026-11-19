package com.branchradar.controller;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.service.BranchService;
import com.branchradar.service.CommitSearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 브랜치별 커밋 내역 화면. 조회 전용이며 GitLab Commits API 를 페이지 단위로 호출한다.
 * q 가 있으면 메시지·작성자·SHA 로 검색한다 ({@link CommitSearchService}).
 */
@Controller
public class CommitsController {

    static final int PER_PAGE = 50;

    private final BranchService branchService;
    private final CommitSearchService searchService;
    private final GitLabClient client;
    private final GitLabProperties properties;

    public CommitsController(BranchService branchService, CommitSearchService searchService,
                             GitLabClient client, GitLabProperties properties) {
        this.branchService = branchService;
        this.searchService = searchService;
        this.client = client;
        this.properties = properties;
    }

    /** branch 가 없으면 기준 브랜치를 보여준다. */
    @GetMapping("/commits")
    public String commits(@RequestParam(required = false) String branch,
                          @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "1") int page,
                          Model model) {
        String name = branch == null || branch.isBlank() ? properties.branch().base() : branch.trim();
        String query = q == null ? "" : q.trim();
        int current = Math.max(1, page);
        model.addAttribute("branch", name);
        model.addAttribute("q", query);
        model.addAttribute("page", current);
        model.addAttribute("perPage", PER_PAGE);
        model.addAttribute("tokenConfigured", properties.gitlab().hasToken());
        try {
            GitLabClient.Project project = client.getProject();
            model.addAttribute("projectName", project.name());
            model.addAttribute("projectWebUrl", project.webUrl());
            model.addAttribute("branchNames", branchService.branchNames());
            if (query.isEmpty()) {
                model.addAttribute("commits", client.listCommits(name, current, PER_PAGE));
            } else {
                model.addAttribute("search", searchService.search(name, query));
            }
        } catch (GitLabException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "commits";
    }
}
