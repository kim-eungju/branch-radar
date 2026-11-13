package com.branchradar.controller;

import com.branchradar.client.GitLabClient;
import com.branchradar.client.GitLabException;
import com.branchradar.config.GitLabProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * SQL 미리보기용 JSON API. 마이그레이션 디렉터리 안의 SQL 파일만 읽을 수 있다
 * (토큰 권한으로 저장소의 다른 파일을 읽어가는 통로가 되지 않도록).
 */
@RestController
public class SqlController {

    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private final GitLabClient client;
    private final GitLabProperties properties;

    public SqlController(GitLabClient client, GitLabProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @GetMapping("/api/sql")
    public ResponseEntity<Map<String, Object>> sql(@RequestParam String ref, @RequestParam String path) {
        if (!isAllowed(path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "마이그레이션 디렉터리의 SQL 파일만 조회할 수 있습니다."));
        }
        try {
            byte[] raw = client.getRawFile(path, ref);
            boolean truncated = raw.length > MAX_BYTES;
            String content = new String(truncated ? Arrays.copyOf(raw, MAX_BYTES) : raw, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of(
                    "path", path,
                    "ref", ref,
                    "size", raw.length,
                    "truncated", truncated,
                    "content", content));
        } catch (GitLabException e) {
            HttpStatus status = e.getStatus() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
        }
    }

    boolean isAllowed(String path) {
        if (path == null || path.contains("..") || path.contains("\\")) return false;
        String dir = properties.flyway().normalizedPath();
        boolean inDir = dir.isEmpty() || path.startsWith(dir + "/");
        boolean sql = properties.flyway().suffixes().stream()
                .anyMatch(s -> path.toLowerCase().endsWith(s.toLowerCase()));
        return inDir && sql;
    }
}
