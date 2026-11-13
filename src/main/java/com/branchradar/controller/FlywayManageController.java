package com.branchradar.controller;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.BranchStatus;
import com.branchradar.dto.FlywayMigration;
import com.branchradar.service.BranchService;
import com.branchradar.service.FlywayCommitService;
import com.branchradar.service.FlywayCommitService.DraftConflictException;
import com.branchradar.service.FlywayCommitService.InvalidDraftException;
import com.branchradar.service.FlywayCommitService.Request;
import com.branchradar.service.FlywayLocalStore;
import com.branchradar.service.FlywayLocalSync;
import com.branchradar.service.FlywayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Flyway 관리 화면(기준 브랜치의 Flyway 목록 + 작성 중인 Flyway)과 임시저장/검증/커밋 API.
 * <p>
 * 쓰기 API 는 application/json 본문만 받는다. 브라우저는 다른 사이트의 form 으로 JSON 본문을 보낼 수 없고
 * (CORS 사전 요청에서 막힘), 이 앱은 CORS 를 허용하지 않으므로 다른 사이트가 사용자 몰래 커밋을 일으키지 못한다.
 */
@Controller
public class FlywayManageController {

    private final FlywayCommitService commitService;
    private final BranchService branchService;
    private final FlywayService flywayService;
    private final FlywayLocalStore store;
    private final FlywayLocalSync localSync;
    private final GitLabClient client;
    private final GitLabProperties properties;

    public FlywayManageController(FlywayCommitService commitService, BranchService branchService,
                                  FlywayService flywayService, FlywayLocalStore store, FlywayLocalSync localSync,
                                  GitLabClient client, GitLabProperties properties) {
        this.commitService = commitService;
        this.branchService = branchService;
        this.flywayService = flywayService;
        this.store = store;
        this.localSync = localSync;
        this.client = client;
        this.properties = properties;
    }

    @GetMapping("/flyway")
    public String page(Model model) {
        String base = properties.branch().base();
        model.addAttribute("base", base);
        model.addAttribute("tokenConfigured", properties.gitlab().hasToken());
        model.addAttribute("migrationPath", properties.flyway().normalizedPath());
        model.addAttribute("workers", FlywayCommitService.WORKERS);
        model.addAttribute("localDir", store.root().toString());
        model.addAttribute("sync", localSync.last());
        model.addAttribute("draft", commitService.draft());
        try {
            GitLabClient.Project project = client.getProject();
            model.addAttribute("projectName", project.name());
            model.addAttribute("projectWebUrl", project.webUrl());

            List<GitLabClient.Branch> branches = client.listBranches().items();
            List<BranchSnapshot> snapshots = branchService.snapshots(branches);
            model.addAttribute("branchNames", branches.stream().map(GitLabClient.Branch::name).sorted().toList());
            model.addAttribute("branchGroups", groupByStatus(flywayService.analyze(snapshots, base).rows()));
            model.addAttribute("expectedFileName",
                    commitService.expectedFileName(FlywayCommitService.versions(snapshots)));

            BranchSnapshot baseSnapshot = snapshots.stream().filter(s -> s.name().equals(base)).findFirst().orElse(null);
            if (baseSnapshot == null) {
                model.addAttribute("listError", "기준 브랜치 '" + base + "' 를 찾을 수 없습니다 (branch-radar.branch.base).");
            } else if (!baseSnapshot.ok()) {
                model.addAttribute("listError", baseSnapshot.error());
            } else {
                List<FlywayMigration> newestFirst = new ArrayList<>(baseSnapshot.versioned());
                Collections.reverse(newestFirst);
                model.addAttribute("baseSnapshot", baseSnapshot);
                model.addAttribute("versioned", newestFirst);
            }
        } catch (GitLabException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "flyway";
    }

    /**
     * 커밋할 브랜치 선택용 묶음. 대시보드와 같은 판정(FlywayService.analyze)으로 최신 / 뒤처짐 / 충돌 위험으로 나누고,
     * 파일 없음·조회 불가 브랜치는 기타 로 모은다 (비어 있으면 화면에 나오지 않음).
     *
     * @param color 상태 색 (ok / warn / danger / neutral)
     */
    public record BranchGroup(String key, String label, String color, String hint, List<String> branches) {
    }

    static List<BranchGroup> groupByStatus(List<BranchStatus> rows) {
        List<BranchGroup> groups = new ArrayList<>();
        groups.add(group(rows, "UP_TO_DATE", "최신", "ok", "전체 최신 Flyway 포함", BranchStatus.Status.UP_TO_DATE));
        groups.add(group(rows, "BEHIND", "뒤처짐", "warn", "최신 Flyway 없음", BranchStatus.Status.BEHIND));
        groups.add(group(rows, "CONFLICT", "충돌 위험", "danger", "같은 버전 · 다른 SQL", BranchStatus.Status.CONFLICT));
        BranchGroup etc = group(rows, "ETC", "기타", "neutral", "파일 없음 · 조회 불가",
                BranchStatus.Status.NO_FILES, BranchStatus.Status.ERROR);
        if (!etc.branches().isEmpty()) groups.add(etc);
        return groups;
    }

    private static BranchGroup group(List<BranchStatus> rows, String key, String label, String color, String hint,
                                     BranchStatus.Status... statuses) {
        List<BranchStatus.Status> wanted = List.of(statuses);
        List<String> names = rows.stream()
                .filter(r -> wanted.contains(r.status()))
                .map(r -> r.branch().name())
                .sorted(Comparator.naturalOrder())
                .toList();
        return new BranchGroup(key, label, color, hint, names);
    }

    @GetMapping("/api/flyway/draft")
    @ResponseBody
    public FlywayCommitService.DraftView draft() {
        return commitService.draft();
    }

    @PostMapping(value = "/api/flyway/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FlywayCommitService.DraftView saveDraft(@RequestBody Request request) {
        return commitService.saveDraft(request);
    }

    @PostMapping(value = "/api/flyway/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FlywayCommitService.Plan validate(@RequestBody Request request) {
        return commitService.plan(request);
    }

    @PostMapping(value = "/api/flyway/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public FlywayCommitService.Committed commit(@RequestBody Request request) {
        return commitService.commit(request);
    }

    /** 검증 오류: 어느 브랜치에도 커밋하지 않았음을 422 와 함께 검증 결과로 알린다. */
    @ExceptionHandler(InvalidDraftException.class)
    public ResponseEntity<Map<String, Object>> invalid(InvalidDraftException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", e.getMessage(), "plan", e.getPlan()));
    }

    /** 다른 사람이 먼저 저장했거나 이미 커밋됨: 저장/커밋하지 않고 409 로 알린다. */
    @ExceptionHandler(DraftConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(DraftConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "draft", e.getCurrent()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(GitLabException.class)
    public ResponseEntity<Map<String, Object>> gitlab(GitLabException e) {
        HttpStatus status = e.getStatus() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
