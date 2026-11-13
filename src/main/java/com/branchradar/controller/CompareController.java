package com.branchradar.controller;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.service.BranchService;
import com.branchradar.service.CompareService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CompareController {

    private final CompareService compareService;
    private final BranchService branchService;
    private final GitLabClient client;
    private final GitLabProperties properties;

    public CompareController(CompareService compareService, BranchService branchService,
                             GitLabClient client, GitLabProperties properties) {
        this.compareService = compareService;
        this.branchService = branchService;
        this.client = client;
        this.properties = properties;
    }

    @GetMapping("/compare")
    public String compare(@RequestParam(required = false) String a,
                          @RequestParam(required = false) String b,
                          Model model) {
        String branchA = a == null || a.isBlank() ? properties.branch().base() : a.trim();
        String branchB = b == null ? "" : b.trim();
        model.addAttribute("a", branchA);
        model.addAttribute("b", branchB);
        model.addAttribute("tokenConfigured", properties.gitlab().hasToken());
        model.addAttribute("migrationPath", properties.flyway().normalizedPath());
        try {
            GitLabClient.Project project = client.getProject();
            model.addAttribute("projectName", project.name());
            model.addAttribute("projectWebUrl", project.webUrl());
            model.addAttribute("branchNames", branchService.branchNames());
            if (!branchB.isEmpty()) {
                if (branchA.equals(branchB)) {
                    model.addAttribute("warning", "A 와 B 가 같은 브랜치입니다. 서로 다른 브랜치를 선택하세요.");
                } else {
                    model.addAttribute("result", compareService.compare(branchA, branchB));
                }
            }
        } catch (GitLabException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "compare";
    }
}
