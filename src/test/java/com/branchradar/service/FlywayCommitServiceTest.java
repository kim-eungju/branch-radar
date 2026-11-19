package com.branchradar.service;

import com.branchradar.TestData;
import com.branchradar.client.GitLabClient;
import com.branchradar.dto.FlywayVersion;
import com.branchradar.service.FlywayCommitService.DraftConflictException;
import com.branchradar.service.FlywayCommitService.InvalidDraftException;
import com.branchradar.service.FlywayCommitService.Plan;
import com.branchradar.service.FlywayCommitService.CommitRequest;
import com.branchradar.service.FlywayCommitService.PostRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 오늘 = 2026-11-12 (Asia/Seoul).
 * develop(보호): 2026/V261110.001, 2026/V261112.001 / release: 2026/V261110.001 / hotfix: 2026/V261112.002(다른 내용)
 */
class FlywayCommitServiceTest {

    static final String PROJECT = TestData.BASE_URL + "/api/v4/projects/group%2Fmy%20project";
    /** 커밋 제목 앞머리: 새 이모지(U+1F426) + 공백 한 칸 */
    static final String BIRD = new String(Character.toChars(0x1F426)) + " ";
    static final String NEXT ="V261112.003__VADA.sql";
    static final String NEXT_PATH = "db/migration/2026/" + NEXT;

    @TempDir
    Path dir;

    MockRestServiceServer server;
    FlywayCommitService service;
    FlywayLocalStore store;
    FlywayLocalSync sync;
    BranchService branchService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        GitLabClient client = new GitLabClient(TestData.props(), builder.build());
        FlywayService flyway = new FlywayService(TestData.props(), client);
        branchService = new BranchService(client, flyway, null, null, TestData.props());
        store = new FlywayLocalStore(dir, new ObjectMapper());
        sync = new FlywayLocalSync(TestData.props(), client, flyway, store);
        // 2026-11-12 09:30 (Asia/Seoul)
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:30:15Z"), ZoneOffset.UTC);
        service = new FlywayCommitService(TestData.props(), client, flyway, branchService, store, clock);

        String develop = "{\"name\":\"develop\",\"protected\":true,\"commit\":{\"id\":\"sha-develop\"}}";
        String release = "{\"name\":\"release\",\"commit\":{\"id\":\"sha-release\"}}";
        String hotfix = "{\"name\":\"hotfix\",\"commit\":{\"id\":\"sha-hotfix\"}}";
        server.expect(manyTimes(), requestTo(PROJECT + "/repository/branches/develop"))
                .andRespond(withSuccess(develop, MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(PROJECT + "/repository/branches?per_page=100&page=1"))
                .andRespond(withSuccess("[" + develop + "," + release + "," + hotfix + "]", MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(treeUrl("sha-develop")))
                .andRespond(withSuccess(tree("2026/V261110.001__VADA.sql:a1", "2026/V261112.001__VADA.sql:b1"), MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(treeUrl("sha-release")))
                .andRespond(withSuccess(tree("2026/V261110.001__VADA.sql:a1"), MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(treeUrl("sha-hotfix")))
                .andRespond(withSuccess(tree("2026/V261112.002__VADA.sql:h1"), MediaType.APPLICATION_JSON));
    }

    @AfterEach
    void tearDown() {
        branchService.shutdown();
    }

    private static String treeUrl(String sha) {
        return PROJECT + "/repository/tree?recursive=true&per_page=100&path=db%2Fmigration&ref=" + sha + "&page=1";
    }

    private static String tree(String... files) {
        StringBuilder sb = new StringBuilder("[");
        for (String f : files) {
            String[] p = f.split(":");
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(p[1]).append("\",\"name\":\"").append(p[0].substring(p[0].lastIndexOf('/') + 1))
                    .append("\",\"type\":\"blob\",\"path\":\"db/migration/").append(p[0]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static PostRequest post(String actor, String description, String sql) {
        return new PostRequest(actor, description, sql, null);
    }

    private static PostRequest as(String actor, long revision) {
        return new PostRequest(actor, null, null, revision);
    }

    private CommitRequest commitBy(String actor, String... branches) {
        return new CommitRequest(actor, List.of(branches), service.board().revision);
    }

    private long rev(long id) {
        return service.board().posts.stream().filter(p -> p.id == id).findFirst().orElseThrow().revision;
    }

    private void expectCommit(String branch, String sha) {
        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.branch").value(branch))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + sha + "\",\"short_id\":\"" + sha.substring(0, 8) + "\"}"));
    }

    // ------------------------------------------------------------------ 파일명 · 파일 내용 · 메시지

    @Test
    void 파일명은_오늘_날짜와_전체_브랜치_기준_다음_순번() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertThat(FlywayCommitService.nextVersion(List.of(), today)).isEqualTo("261112.001");
        List<FlywayVersion> all = Stream.of("261110.007", "261112.001", "261112.002", "250101.900")
                .map(r -> FlywayVersion.parse(r, true)).toList();
        assertThat(FlywayCommitService.nextVersion(all, today)).isEqualTo("261112.003");
    }

    @Test
    void 조각을_순서대로_합치고_등록자와_설명을_주석으로_넣는다() {
        service.createPost(post("ejkim", "주문 테이블 컬럼 추가", "ALTER TABLE orders ADD memo TEXT;\r\n\r\n"));
        service.createPost(post("jwhan", "코드 데이터\n두 번째 줄", "INSERT INTO code VALUES (1);"));
        service.updatePost(2, new PostRequest("jwsung", "코드 데이터\n두 번째 줄", "INSERT INTO code VALUES (2);", rev(2)));

        assertThat(service.content(service.board())).isEqualTo("""
                -- ============================================================
                -- [1/2] 등록자 : ejkim | 등록 : 2026-11-12 09:30
                -- 설명 : 주문 테이블 컬럼 추가
                -- ============================================================
                ALTER TABLE orders ADD memo TEXT;

                -- ============================================================
                -- [2/2] 등록자 : jwhan | 등록 : 2026-11-12 09:30 | 수정 : jwsung 2026-11-12 09:30
                -- 설명 : 코드 데이터
                --        두 번째 줄
                -- ============================================================
                INSERT INTO code VALUES (2);
                """);
    }

    @Test
    void 커밋_메시지에_등록자_전원과_커밋한_사람과_조각별_설명이_들어간다() {
        service.createPost(post("ejkim", "컬럼 추가", "SELECT 1;"));
        assertThat(FlywayCommitService.commitMessage("V261102.001__VADA.sql", "jwhan", service.board().getActive()))
                .isEqualTo(BIRD + "V261102.001__VADA.sql\n\n등록자 : ejkim\n커밋 : jwhan\n설명 : 컬럼 추가");

        service.createPost(post("jwhan", "코드 추가\n자세한 내용", "SELECT 2;"));
        service.createPost(post("ejkim", "인덱스", "SELECT 3;"));
        assertThat(FlywayCommitService.commitMessage("V261102.001__VADA.sql", "jwhan", service.board().getActive()))
                .isEqualTo(BIRD + "V261102.001__VADA.sql\n\n등록자 : ejkim, jwhan\n커밋 : jwhan\n설명 :\n"
                        + "- 컬럼 추가 (ejkim)\n- 코드 추가 (jwhan)\n- 인덱스 (ejkim)");
    }

    @Test
    void git_blob_SHA는_git_hash_object_와_같다() {
        // printf 'hello\n' | git hash-object --stdin
        assertThat(FlywayCommitService.blobSha("hello\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
    }

    // ------------------------------------------------------------------ 묶음

    @Test
    void 조각은_누구나_고치고_지울_수_있고_모든_변경이_이력에_남는다() {
        service.createPost(post("ejkim", "A", "SELECT 'a';"));
        service.createPost(post("jwhan", "B", "SELECT 'b';"));
        service.createPost(post("jwsung", "C", "SELECT 'c';"));

        service.updatePost(1, new PostRequest("jwhan", "A2", "SELECT 'a2';", rev(1)));
        service.deletePost(2, as("jwsung", rev(2)));
        service.movePost(3, true, as("ejkim", 0));          // 남은 조각 사이에서 C 를 A 위로

        FlywayBoard board = service.board();
        assertThat(board.getActive()).extracting(p -> p.description).containsExactly("C", "A2");
        assertThat(board.getDeleted()).singleElement().satisfies(p -> {
            assertThat(p.sql).isEqualTo("SELECT 'b';");     // 삭제해도 내용은 남는다
            assertThat(p.deletedBy).isEqualTo("jwsung");
        });
        assertThat(board.getAuthors()).containsExactly("jwsung", "ejkim");
        assertThat(board.log).extracting(e -> e.who + " " + e.action.getLabel() + " #" + e.postId).containsExactly(
                "ejkim 등록 #1", "jwhan 등록 #2", "jwsung 등록 #3", "jwhan 수정 #1", "jwsung 삭제 #2", "ejkim 순서 변경 #3");
        // 수정·삭제 전 내용이 이력에 남는다
        assertThat(board.log.get(3).beforeSql).isEqualTo("SELECT 'a';");
        assertThat(board.log.get(3).detail).contains("작성 ejkim");
        assertThat(board.log.get(4).beforeSql).isEqualTo("SELECT 'b';");

        // 복구하면 맨 아래로
        service.restorePost(2, as("ejkim", 0));
        assertThat(service.board().getActive()).extracting(p -> p.description).containsExactly("C", "A2", "B");
        assertThat(service.board().getLastLog().action).isEqualTo(FlywayBoard.Action.RESTORE);
    }

    @Test
    void 조각_수정은_revision_으로_다른_사람의_변경을_덮어쓰지_않게_한다() {
        service.createPost(post("ejkim", "A", "SELECT 1;"));
        long seen = rev(1);
        service.updatePost(1, new PostRequest("jwhan", "A", "SELECT 2;", seen));

        assertThatThrownBy(() -> service.updatePost(1, new PostRequest("jwsung", "A", "SELECT 3;", seen)))
                .isInstanceOf(DraftConflictException.class).hasMessageContaining("jwhan");
        assertThatThrownBy(() -> service.deletePost(1, as("jwsung", seen)))
                .isInstanceOf(DraftConflictException.class);
        assertThat(service.board().getActive().get(0).sql).isEqualTo("SELECT 2;");
    }

    @Test
    void 작업자_설명_SQL은_필수() {
        assertThatThrownBy(() -> service.createPost(post("someone", "A", "SELECT 1;")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("작업자");
        assertThatThrownBy(() -> service.createPost(post("ejkim", " ", "SELECT 1;")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("설명");
        assertThatThrownBy(() -> service.createPost(post("ejkim", "A", "  \n")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SQL");
        assertThat(store.readBoard()).isNull();
    }

    @Test
    void 이전_임시저장은_조각_하나로_옮긴다() throws Exception {
        Files.writeString(dir.resolve(".draft.json"), "{\"sql\":\"SELECT 'old';\",\"description\":\"예전 작업\","
                + "\"worker\":\"jwhan\",\"branches\":[\"develop\"],\"revision\":5,\"updatedAt\":\"2026-11-11 18:00\"}");

        FlywayBoard board = service.board();

        assertThat(board.getActive()).singleElement().satisfies(p -> {
            assertThat(p.author).isEqualTo("jwhan");
            assertThat(p.description).isEqualTo("예전 작업");
            assertThat(p.sql).isEqualTo("SELECT 'old';");
            assertThat(p.createdAt).isEqualTo("2026-11-11 18:00");
        });
        assertThat(board.log).singleElement().satisfies(e -> assertThat(e.action).isEqualTo(FlywayBoard.Action.MIGRATE));
        assertThat(dir.resolve(".draft.json")).doesNotExist();
        assertThat(dir.resolve(".board.json")).exists();
    }

    // ------------------------------------------------------------------ 검증

    @Test
    void 파일명과_경로는_자동으로_정해지고_기준_브랜치의_최신_파일과_같은_폴더에_둔다() {
        service.createPost(post("ejkim", "사용자 테이블 추가", "SELECT 1;\r\nSELECT 2;"));
        Plan plan = service.plan(commitBy("jwhan", "develop", "release"));

        assertThat(plan.ok()).isTrue();
        assertThat(plan.fileName()).isEqualTo(NEXT);          // hotfix 의 .002 다음
        assertThat(plan.filePath()).isEqualTo(NEXT_PATH);
        assertThat(plan.commitMessage()).isEqualTo(BIRD + NEXT + "\n\n등록자 : ejkim\n커밋 : jwhan\n설명 : 사용자 테이블 추가");
        assertThat(plan.posts()).isEqualTo(1);
        assertThat(plan.sqlLines()).isEqualTo(6);              // 주석 4줄 + SQL 2줄
        assertThat(plan.content()).endsWith("SELECT 1;\nSELECT 2;\n");
        assertThat(plan.branches()).extracting(b -> b.branch()).containsExactly("develop", "release");
        assertThat(plan.branches().get(0).warnings()).anyMatch(w -> w.contains("보호 브랜치"));
        assertThat(plan.branches().get(1).warnings()).isEmpty();
    }

    @Test
    void 작업자와_브랜치와_조각은_필수() {
        Plan plan = service.plan(new CommitRequest("someone", List.of(), 0L));
        assertThat(plan.ok()).isFalse();
        assertThat(plan.errors()).hasSize(3)
                .anyMatch(e -> e.contains("작업자"))
                .anyMatch(e -> e.contains("조각이 없습니다"))
                .anyMatch(e -> e.contains("브랜치"));
        assertThat(service.workers()).containsExactly("jwsung", "ejkim", "jwhan", "chwonhan00");
    }

    @Test
    void 세미콜론으로_끝나지_않는_조각은_경고() {
        service.createPost(post("ejkim", "A", "SELECT 1 -- 끝에 ; 없음"));
        service.createPost(post("jwhan", "B", "SELECT 2; -- 주석"));
        Plan plan = service.plan(commitBy("ejkim", "release"));
        assertThat(plan.ok()).isTrue();
        assertThat(plan.warnings()).singleElement().satisfies(w -> assertThat(w).contains("#1", "ejkim", ";"));
    }

    @Test
    void 없는_브랜치가_섞여_있으면_전체가_커밋_불가() {
        service.createPost(post("ejkim", "A", "SELECT 1;"));
        Plan plan = service.plan(commitBy("ejkim", "develop", "no-such"));
        assertThat(plan.ok()).isFalse();
        assertThat(plan.branches().get(1).errors()).anyMatch(e -> e.contains("찾을 수 없습니다"));
    }

    // ------------------------------------------------------------------ 커밋

    @Test
    void 여러_브랜치에_합친_파일을_커밋하고_로컬에_저장하고_묶음을_보관한_뒤_비운다() throws Exception {
        service.createPost(post("ejkim", "사용자 테이블 추가", "CREATE TABLE users (id INT);"));
        service.createPost(post("jwhan", "인덱스", "CREATE INDEX ix ON users (id);"));
        service.deletePost(2, as("jwsung", rev(2)));
        String content = service.content(service.board());

        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PRIVATE-TOKEN", "test-token"))
                .andExpect(jsonPath("$.branch").value("develop"))
                .andExpect(jsonPath("$.commit_message").value(BIRD + NEXT + "\n\n등록자 : ejkim\n커밋 : jwhan\n설명 : 사용자 테이블 추가"))
                .andExpect(jsonPath("$.author_name").doesNotExist())
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].action").value("create"))
                .andExpect(jsonPath("$.actions[0].file_path").value(NEXT_PATH))
                .andExpect(jsonPath("$.actions[0].content").value(content))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"aaaaaaaa11111111\",\"short_id\":\"aaaaaaaa\"}"));
        expectCommit("release", "bbbbbbbb22222222");

        var done = service.commit(commitBy("jwhan", "develop", "release"));

        assertThat(done.allSucceeded()).isTrue();
        assertThat(done.results()).extracting(r -> r.branch() + ":" + r.shortSha())
                .containsExactly("develop:aaaaaaaa", "release:bbbbbbbb");
        assertThat(Files.readString(dir.resolve("2026").resolve(NEXT))).isEqualTo(content);
        assertThat(content).contains("-- [1/1] 등록자 : ejkim").doesNotContain("CREATE INDEX");

        // 묶음은 비우고, 조각·삭제된 조각·이력은 history 에 보관
        assertThat(store.readBoard()).isNull();
        assertThat(done.archive()).isEqualTo("V261112.003__VADA");
        assertThat(service.archives()).containsExactly("V261112.003__VADA");
        FlywayBoard.Archive archive = service.archive("V261112.003__VADA");
        assertThat(archive.content).isEqualTo(content);
        assertThat(archive.committedBranches).containsExactly("develop", "release");
        assertThat(archive.board.getDeleted()).singleElement().satisfies(p -> assertThat(p.sql).contains("CREATE INDEX"));
        assertThat(archive.board.getLastLog().action).isEqualTo(FlywayBoard.Action.COMMIT);
    }

    @Test
    void 다른_사람이_바꾼_묶음을_모르고_커밋하지_않는다() {
        service.createPost(post("ejkim", "A", "SELECT 1;"));
        long seen = service.board().revision;
        service.createPost(post("jwhan", "B", "SELECT 2;"));

        assertThatThrownBy(() -> service.commit(new CommitRequest("ejkim", List.of("release"), seen)))
                .isInstanceOf(DraftConflictException.class).hasMessageContaining("jwhan").hasMessageContaining("등록");
    }

    @Test
    void 검증_오류가_있으면_어느_브랜치에도_커밋_요청을_보내지_않는다() {
        // POST /repository/commits 기대값을 등록하지 않았으므로 호출하면 MockRestServiceServer 가 실패시킨다
        service.createPost(post("ejkim", "A", "SELECT 1;"));
        assertThatThrownBy(() -> service.commit(commitBy("", "develop", "release")))
                .isInstanceOf(InvalidDraftException.class)
                .satisfies(e -> assertThat(((InvalidDraftException) e).getPlan().errors()).anyMatch(m -> m.contains("작업자")));
        assertThat(dir.resolve("2026")).doesNotExist();
    }

    @Test
    void 일부_브랜치만_실패하면_버전과_내용을_고정하고_조각_수정을_막는다() {
        service.createPost(post("ejkim", "A", "SELECT 1;"));
        String content = service.content(service.board());
        expectCommit("release", "cccccccc33333333");
        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(jsonPath("$.branch").value("develop"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"You are not allowed to push into this branch\"}"));

        var done = service.commit(commitBy("chwonhan00", "develop", "release"));

        assertThat(done.allSucceeded()).isFalse();
        assertThat(done.results().get(0).error()).contains("권한 없음(403)", "Allowed to push",
                "You are not allowed to push into this branch");
        assertThat(done.results().get(1).success()).isTrue();
        assertThat(done.localFile()).endsWith(NEXT);
        assertThat(done.archive()).isNull();

        FlywayBoard board = service.board();
        assertThat(board.isLocked()).isTrue();
        assertThat(board.pinnedVersion).isEqualTo("261112.003");
        assertThat(board.pinnedContent).isEqualTo(content);
        assertThat(board.pendingBranches).containsExactly("develop");
        assertThat(board.committedBranches).containsExactly("release");
        assertThat(service.expectedFileName(List.of())).isEqualTo(NEXT);
        assertThat(board.getLastLog().action).isEqualTo(FlywayBoard.Action.PARTIAL_COMMIT);

        // 남은 브랜치에 같은 파일이 올라가야 하므로 조각을 바꿀 수 없다
        assertThatThrownBy(() -> service.createPost(post("ejkim", "B", "SELECT 2;")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("일부 브랜치");
        assertThatThrownBy(() -> service.deletePost(1, as("ejkim", rev(1))))
                .isInstanceOf(IllegalArgumentException.class);
        // 이미 커밋한 브랜치에 다시 커밋할 수 없다
        assertThat(service.plan(commitBy("ejkim", "release")).errors()).anyMatch(e -> e.contains("이미 이 파일을 커밋한"));

        // 마감: 남은 브랜치는 커밋하지 않고 보관
        String name = service.close(commitBy("ejkim"));
        assertThat(store.readBoard()).isNull();
        FlywayBoard.Archive archive = service.archive(name);
        assertThat(archive.fileName).isEqualTo(NEXT);
        assertThat(archive.committedBranches).containsExactly("release");
        assertThat(archive.abandonedBranches).containsExactly("develop");
        assertThat(archive.content).isEqualTo(content);
    }

    // ------------------------------------------------------------------ 로컬 동기화

    @Test
    void 기준_브랜치의_Flyway를_로컬에_내려받고_이미_있는_파일은_건너뛴다() throws Exception {
        Files.createDirectories(dir.resolve("2026"));
        Files.writeString(dir.resolve("2026/V261110.001__VADA.sql"), "local copy");
        server.expect(once(), requestTo(PROJECT + "/repository/files/db%2Fmigration%2F2026%2FV261112.001__VADA.sql/raw?ref=sha-develop"))
                .andRespond(withSuccess("SELECT 'b';", MediaType.TEXT_PLAIN));

        var result = sync.syncBase();

        assertThat(result).extracting(r -> r.total(), r -> r.saved(), r -> r.skipped(), r -> r.failed())
                .containsExactly(2, 1, 1, 0);
        assertThat(Files.readString(dir.resolve("2026/V261110.001__VADA.sql"))).isEqualTo("local copy");
        assertThat(Files.readString(dir.resolve("2026/V261112.001__VADA.sql"))).isEqualTo("SELECT 'b';");
        assertThat(sync.last()).isEqualTo(result);
    }

    @Test
    void 로컬_폴더_밖으로_나가는_경로는_거부한다() {
        assertThatThrownBy(() -> store.write("../outside.sql", new byte[0], true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
