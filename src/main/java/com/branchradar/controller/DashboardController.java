package com.branchradar.controller;

import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.DashboardView;
import com.branchradar.dto.FlywayVersion;
import com.branchradar.service.BranchService;
import com.branchradar.service.FlywayCommitService;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private final BranchService branchService;
    private final FlywayCommitService commitService;
    private final GitLabProperties properties;

    public DashboardController(BranchService branchService, FlywayCommitService commitService,
                               GitLabProperties properties) {
        this.branchService = branchService;
        this.commitService = commitService;
        this.properties = properties;
    }

    /** base 파라미터로 기준 브랜치를 바꾼다 (저장하지 않음, 요청 단위). */
    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) String base, Model model) {
        model.addAttribute("tokenConfigured", properties.gitlab().hasToken());
        model.addAttribute("migrationPath", properties.flyway().normalizedPath());
        try {
            DashboardView view = branchService.dashboard(base);
            model.addAttribute("view", view);
            // 작성 중인 Flyway (커밋되면 가장 최신이 될 파일)
            FlywayCommitService.DraftView draft = commitService.draft();
            if (draft.exists()) {
                List<FlywayVersion> versions = new ArrayList<>();
                view.rows().forEach(r -> r.branch().versioned().forEach(m -> versions.add(m.version())));
                model.addAttribute("draft", draft);
                model.addAttribute("draftFileName", commitService.expectedFileName(versions));
            }
        } catch (GitLabException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "dashboard";
    }
}
