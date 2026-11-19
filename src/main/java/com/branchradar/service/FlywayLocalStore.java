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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 로컬 폴더(branch-radar.flyway.local-dir) 입출력.
 * <ul>
 *   <li>Flyway 파일: 저장소의 마이그레이션 디렉터리 기준 상대 경로 그대로 저장한다 (하위 폴더 유지)</li>
 *   <li>이번 묶음: .board.json 한 개</li>
 *   <li>커밋이 끝난 묶음: history/{파일명}.json</li>
 * </ul>
 * GitLab 을 호출하지 않는다.
 */
@Component
public class FlywayLocalStore {

    private static final String BOARD_FILE = ".board.json";
    /** 예전 임시저장 파일. 남아 있으면 묶음 조각 하나로 옮긴 뒤 지운다 */
    private static final String LEGACY_DRAFT_FILE = ".draft.json";
    private static final String HISTORY_DIR = "history";

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
     * 예전 임시저장 (모든 작업자가 함께 쓰던 SQL 한 개). 옮기기 위해서만 읽는다.
     */
    public record LegacyDraft(String sql, String description, String worker, List<String> branches,
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

    // ---------------------------------------------------------------- 묶음

    /** @return 묶음 파일이 없으면 null */
    public FlywayBoard readBoard() {
        return readJson(root.resolve(BOARD_FILE), FlywayBoard.class, "묶음");
    }

    public void writeBoard(FlywayBoard board) {
        writeJson(root.resolve(BOARD_FILE), board, "묶음");
    }

    public void deleteBoard() {
        delete(root.resolve(BOARD_FILE), "묶음");
    }

    /** @return 이전 임시저장 파일이 없으면 null */
    public LegacyDraft readLegacyDraft() {
        return readJson(root.resolve(LEGACY_DRAFT_FILE), LegacyDraft.class, "이전 임시저장");
    }

    public void deleteLegacyDraft() {
        delete(root.resolve(LEGACY_DRAFT_FILE), "이전 임시저장");
    }

    // ---------------------------------------------------------------- 보관 (history)

    /** 커밋이 끝난 묶음을 보관한다. 같은 파일명이 이미 있으면 -2, -3 을 붙인다. */
    public String writeArchive(FlywayBoard.Archive archive) {
        String base = archiveName(archive.fileName);
        Path dir = root.resolve(HISTORY_DIR);
        String name = base;
        for (int i = 2; Files.exists(dir.resolve(name + ".json")); i++) {
            name = base + "-" + i;
        }
        writeJson(dir.resolve(name + ".json"), archive, "보관 파일");
        return name;
    }

    /** 보관된 묶음 이름 (최신 파일명부터) */
    public List<String> listArchives() {
        Path dir = root.resolve(HISTORY_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>(files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .toList());
            names.sort(Comparator.reverseOrder());
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("보관 폴더를 읽지 못했습니다: " + dir, e);
        }
    }

    /** @return 없으면 null */
    public FlywayBoard.Archive readArchive(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("잘못된 보관 이름입니다: " + name);
        }
        return readJson(root.resolve(HISTORY_DIR).resolve(name + ".json"), FlywayBoard.Archive.class, "보관 파일");
    }

    /** V261119.001__VADA.sql → V261119.001__VADA (파일 이름에 쓸 수 없는 글자는 _) */
    static String archiveName(String fileName) {
        String name = fileName == null ? "unknown" : fileName.replaceFirst("\\.[^.]*$", "");
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ---------------------------------------------------------------- JSON

    private <T> T readJson(Path file, Class<T> type, String what) {
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            throw new UncheckedIOException(what + " 파일을 읽지 못했습니다: " + file, e);
        }
    }

    private void writeJson(Path file, Object value, String what) {
        try {
            Files.createDirectories(file.getParent());
            // 쓰는 도중 중단되어도 기존 파일이 깨지지 않게 임시 파일에 쓴 뒤 바꿔치기한다
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(what + " 파일을 쓰지 못했습니다: " + file, e);
        }
    }

    private static void delete(Path file, String what) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(what + " 파일을 지우지 못했습니다: " + file, e);
        }
    }
}
