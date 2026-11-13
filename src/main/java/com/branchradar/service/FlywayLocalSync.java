package com.branchradar.service;

import com.branchradar.client.GitLabClient;
import com.branchradar.config.GitLabProperties;
import com.branchradar.dto.FlywayMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 기준 브랜치의 Flyway 파일을 로컬 폴더로 내려받는다. 이미 있는 파일은 건너뛴다(덮어쓰지 않음).
 * 시작할 때 한 번 백그라운드로 실행하므로, GitLab 이 느리거나 죽어 있어도 애플리케이션 시작은 막지 않는다.
 */
@Component
public class FlywayLocalSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayLocalSync.class);

    private final GitLabProperties properties;
    private final GitLabClient client;
    private final FlywayService flywayService;
    private final FlywayLocalStore store;

    private volatile Result last;

    public FlywayLocalSync(GitLabProperties properties, GitLabClient client, FlywayService flywayService,
                           FlywayLocalStore store) {
        this.properties = properties;
        this.client = client;
        this.flywayService = flywayService;
        this.store = store;
    }

    /**
     * @param error 전체가 실패했을 때의 사유 (브랜치 조회 실패 등). 정상이면 null
     */
    public record Result(String branch, int total, int saved, int skipped, int failed, String error) {
    }

    /** 가장 최근 동기화 결과 (아직 끝나지 않았으면 null) */
    public Result last() {
        return last;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.flyway().syncOnStartup()) return;
        Thread t = new Thread(this::syncBase, "flyway-local-sync");
        t.setDaemon(true);
        t.start();
    }

    public Result syncBase() {
        String branch = properties.branch().base();
        Result result;
        try {
            String sha = client.getBranch(branch).commit().id();
            FlywayService.MigrationSet set = flywayService.loadMigrations(sha);
            List<FlywayMigration> files = new ArrayList<>(set.versioned());
            files.addAll(set.repeatable());

            int saved = 0, skipped = 0, failed = 0;
            for (FlywayMigration m : files) {
                String relative = flywayService.relativePath(m.path());
                try {
                    if (store.exists(relative)) {
                        skipped++;
                    } else {
                        store.write(relative, client.getRawFile(m.path(), sha), false);
                        saved++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Flyway 파일 로컬 저장 실패: {} — {}", m.path(), e.getMessage());
                }
            }
            result = new Result(branch, files.size(), saved, skipped, failed, null);
            log.info("Flyway 로컬 동기화({}): 전체 {} · 새로 저장 {} · 이미 있음 {} · 실패 {} → {}",
                    branch, files.size(), saved, skipped, failed, store.root());
        } catch (RuntimeException e) {
            result = new Result(branch, 0, 0, 0, 0, e.getMessage());
            log.warn("Flyway 로컬 동기화({}) 실패: {}", branch, e.getMessage());
        }
        last = result;
        return result;
    }
}
