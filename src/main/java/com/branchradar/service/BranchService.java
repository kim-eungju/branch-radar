package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.BranchActivity;
import com.branchradar.dto.BranchSnapshot;
import com.branchradar.dto.DashboardView;
import com.branchradar.dto.TagSummary;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 대시보드: 전체 브랜치 조회 → 브랜치별 Flyway 목록 → 분석.
 */
@Service
public class BranchService {

    private final GitLabClient client;
    private final FlywayService flywayService;
    private final TagService tagService;
    private final BranchActivityService activityService;
    private final GitLabProperties properties;
    private final ExecutorService executor;

    public BranchService(GitLabClient client, FlywayService flywayService, TagService tagService,
                         BranchActivityService activityService, GitLabProperties properties) {
        this.client = client;
        this.flywayService = flywayService;
        this.tagService = tagService;
        this.activityService = activityService;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.branch().fetchConcurrency()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public String defaultBase() {
        return properties.branch().base();
    }

    /**
     * 프로젝트/브랜치 목록 조회가 실패하면 GitLabException 을 던진다(화면에서 오류로 표시).
     * 개별 브랜치의 Flyway 조회 실패는 해당 브랜치만 '조회 불가' 로 표시한다.
     */
    public DashboardView dashboard(String baseBranch) {
        GitLabClient.Project project = client.getProject();
        GitLabClient.Paged<GitLabClient.Branch> branchPage = client.listBranches();
        List<GitLabClient.Branch> branches = branchPage.items();

        List<String> notices = new ArrayList<>();
        if (!branchPage.complete()) {
            notices.add("브랜치가 너무 많아 " + branches.size() + "개만 조회되었습니다 (max-pages 제한). "
                    + "전체 최신 Flyway 판정이 전체 브랜치 기준이 아닐 수 있습니다.");
        }

        List<BranchSnapshot> snapshots = loadSnapshots(branches);
        long failed = snapshots.stream().filter(s -> !s.ok()).count();
        if (failed > 0) {
            notices.add(failed + "개 브랜치의 Flyway 목록을 조회하지 못했습니다. 해당 브랜치는 '조회 불가' 로 표시되며, "
                    + "전체 최신 Flyway 판정에서 제외되었습니다.");
        }

        String base = baseBranch == null || baseBranch.isBlank() ? defaultBase() : baseBranch.trim();
        boolean baseFound = snapshots.stream().anyMatch(s -> s.name().equals(base) && s.ok());
        if (!baseFound) {
            notices.add("기준 브랜치 '" + base + "' 를 찾을 수 없거나 조회하지 못해 기준 브랜치 대비 비교를 생략했습니다.");
        }

        FlywayService.DashboardAnalysis analysis = flywayService.analyze(snapshots, base);
        List<String> names = branches.stream().map(GitLabClient.Branch::name).sorted().toList();
        TagSummary tags = tagService.summarize(names);

        // 브랜치 목록 응답의 마지막 커밋 날짜를 그대로 사용 (추가 API 호출 없음)
        Map<String, BranchActivity> activities = new HashMap<>();
        for (GitLabClient.Branch b : branches) {
            activities.put(b.name(), activityService.of(b.commit() == null ? null : b.commit().committedDate()));
        }

        return new DashboardView(project.name(), project.webUrl(), base, baseFound, names,
                analysis.rows(), analysis.latest(), analysis.latestRefBranch(), tags,
                activities, activityService.warningDays(), activityService.dangerDays(), notices);
    }

    public List<String> branchNames() {
        return client.listBranches().items().stream().map(GitLabClient.Branch::name)
                .sorted(Comparator.naturalOrder()).toList();
    }

    /** 전체 브랜치의 Flyway 스냅샷. 개별 브랜치 조회 실패는 해당 스냅샷의 error 로만 남는다. */
    public List<BranchSnapshot> snapshots() {
        return loadSnapshots(client.listBranches().items());
    }

    /** 이미 조회한 브랜치 목록으로 스냅샷을 만든다 (브랜치 목록을 다시 조회하지 않음). */
    public List<BranchSnapshot> snapshots(List<GitLabClient.Branch> branches) {
        return loadSnapshots(branches);
    }

    /** 브랜치별 Flyway 목록. 같은 커밋을 가리키는 브랜치는 한 번만 조회한다. */
    private List<BranchSnapshot> loadSnapshots(List<GitLabClient.Branch> branches) {
        Map<String, Future<FlywayService.MigrationSet>> bySha = new LinkedHashMap<>();
        for (GitLabClient.Branch b : branches) {
            String sha = b.commit().id();
            bySha.computeIfAbsent(sha, s -> executor.submit(() -> flywayService.loadMigrations(s)));
        }
        List<BranchSnapshot> result = new ArrayList<>();
        for (GitLabClient.Branch b : branches) {
            result.add(toSnapshot(b, bySha.get(b.commit().id())));
        }
        return result;
    }

    private BranchSnapshot toSnapshot(GitLabClient.Branch b, Future<FlywayService.MigrationSet> future) {
        try {
            return snapshot(b, future.get());
        } catch (ExecutionException e) {
            String msg = e.getCause() instanceof GitLabException ge ? ge.getMessage() : "조회 실패: " + e.getCause();
            return errorSnapshot(b, msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorSnapshot(b, "조회 중단됨");
        }
    }

    public BranchSnapshot snapshot(GitLabClient.Branch b, FlywayService.MigrationSet set) {
        GitLabClient.Commit c = b.commit();
        return new BranchSnapshot(b.name(), c.id(), c.committedDate(), c.title(),
                set.versioned(), set.repeatable(), set.ignored(), set.dirMissing(), null);
    }

    private static BranchSnapshot errorSnapshot(GitLabClient.Branch b, String error) {
        GitLabClient.Commit c = b.commit();
        return new BranchSnapshot(b.name(), c.id(), c.committedDate(), c.title(),
                List.of(), List.of(), List.of(), false, error);
    }
}
