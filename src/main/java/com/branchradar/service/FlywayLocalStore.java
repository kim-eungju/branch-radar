package com.branchradar.service;

import com.branchradar.config.GitLabProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 로컬 폴더(branch-radar.flyway.local-dir) 입출력.
 * <ul>
 *   <li>Flyway 파일: 저장소의 마이그레이션 디렉터리 기준 상대 경로 그대로 저장한다 (하위 폴더 유지)</li>
 *   <li>작성 중인 임시저장: .draft.json 한 개</li>
 * </ul>
 * GitLab 을 호출하지 않는다.
 */
@Component
public class FlywayLocalStore {

    private static final String DRAFT_FILE = ".draft.json";

    private final Path root;
    private final ObjectMapper mapper;

    @Autowired
    public FlywayLocalStore(GitLabProperties properties, ObjectMapper mapper) {
        this(Path.of(properties.flyway().localDir()), mapper);
    }

    public FlywayLocalStore(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    /**
     * 작성 중인 Flyway (모든 작업자가 함께 쓰는 한 개).
     *
     * @param revision      저장할 때마다 커지는 값. 다른 사람이 먼저 저장했는지 확인하는 데 쓴다
     * @param pinnedVersion 일부 브랜치에만 커밋된 경우 그 버전. 남은 브랜치에도 같은 파일명으로 올리기 위해 고정한다
     */
    public record Draft(String sql, String description, String worker, List<String> branches,
                        long revision, String updatedAt, String pinnedVersion) {
    }

    public Path root() {
        return root;
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /**
     * @param overwrite false 면 이미 있는 파일은 그대로 두고 false 를 반환한다
     * @return 파일을 썼으면 true
     */
    public boolean write(String relativePath, byte[] content, boolean overwrite) throws IOException {
        Path target = resolve(relativePath);
        if (!overwrite && Files.exists(target)) return false;
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        return true;
    }

    /** 로컬 폴더 밖으로 나가는 경로는 거부한다 (저장소 파일 경로를 그대로 쓰기 때문). */
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("로컬 폴더 밖의 경로입니다: " + relativePath);
        }
        return target;
    }

    /** @return 임시저장이 없으면 null */
    public Draft readDraft() {
        Path file = root.resolve(DRAFT_FILE);
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(Files.readAllBytes(file), Draft.class);
        } catch (IOException e) {
            throw new UncheckedIOException("임시저장 파일을 읽지 못했습니다: " + file, e);
        }
    }

    public void writeDraft(Draft draft) {
        Path file = root.resolve(DRAFT_FILE);
        try {
            Files.createDirectories(root);
            // 쓰는 도중 중단되어도 기존 임시저장이 깨지지 않게 임시 파일에 쓴 뒤 바꿔치기한다
            Path tmp = root.resolve(DRAFT_FILE + ".tmp");
            Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(draft));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("임시저장 파일을 쓰지 못했습니다: " + file, e);
        }
    }

    public void deleteDraft() {
        try {
            Files.deleteIfExists(root.resolve(DRAFT_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException("임시저장 파일을 지우지 못했습니다.", e);
        }
    }
}
