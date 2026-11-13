package com.branchradar.service;

import com.branchradar.TestData;
import com.branchradar.client.GitLabClient;
import com.branchradar.dto.FlywayVersion;
import com.branchradar.service.FlywayCommitService.DraftConflictException;
import com.branchradar.service.FlywayCommitService.InvalidDraftException;
import com.branchradar.service.FlywayCommitService.Plan;
import com.branchradar.service.FlywayCommitService.Request;
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

    private static Request request(String sql, String worker, String... branches) {
        return new Request(sql, "사용자 테이블 추가", worker, List.of(branches), 0L);
    }

    private void expectCommit(String branch, String sha) {
        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.branch").value(branch))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + sha + "\",\"short_id\":\"" + sha.substring(0, 8) + "\"}"));
    }

    // ------------------------------------------------------------------ 파일명

    @Test
    void 파일명은_오늘_날짜와_전체_브랜치_기준_다음_순번() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertThat(FlywayCommitService.nextVersion(List.of(), today)).isEqualTo("261112.001");
        List<FlywayVersion> all = Stream.of("261110.007", "261112.001", "261112.002", "250101.900")
                .map(r -> FlywayVersion.parse(r, true)).toList();
        assertThat(FlywayCommitService.nextVersion(all, today)).isEqualTo("261112.003");
    }

    @Test
    void 커밋_메시지_양식() {
        assertThat(FlywayCommitService.commitMessage("V261102.001__VADA.sql", "jwhan", "컬럼 추가"))
                .isEqualTo(BIRD + "V261102.001__VADA.sql\n\n등록자 : jwhan\n설명 : 컬럼 추가");
        // 설명은 선택
        assertThat(FlywayCommitService.commitMessage("V261102.001__VADA.sql", "ejkim", ""))
                .isEqualTo(BIRD + "V261102.001__VADA.sql\n\n등록자 : ejkim\n설명 :");
    }

    @Test
    void git_blob_SHA는_git_hash_object_와_같다() {
        // printf 'hello\n' | git hash-object --stdin
        assertThat(FlywayCommitService.blobSha("hello\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ce013625030ba8dba906f756967f9e9ca394464a");
    }

    // ------------------------------------------------------------------ 검증

    @Test
    void 파일명과_경로는_자동으로_정해지고_기준_브랜치의_최신_파일과_같은_폴더에_둔다() {
        Plan plan = service.plan(request("SELECT 1;\r\nSELECT 2;", "jwhan", "develop", "release"));

        assertThat(plan.ok()).isTrue();
        assertThat(plan.fileName()).isEqualTo(NEXT);          // hotfix 의 .002 다음
        assertThat(plan.filePath()).isEqualTo(NEXT_PATH);
        assertThat(plan.commitMessage()).isEqualTo(BIRD + "" + NEXT + "\n\n등록자 : jwhan\n설명 : 사용자 테이블 추가");
        assertThat(plan.sqlLines()).isEqualTo(2);
        assertThat(plan.branches()).extracting(b -> b.branch()).containsExactly("develop", "release");
        assertThat(plan.branches().get(0).warnings()).anyMatch(w -> w.contains("보호 브랜치"));
        assertThat(plan.branches().get(1).warnings()).isEmpty();
    }

    @Test
    void 등록자와_브랜치와_SQL은_필수이고_등록자는_정해진_사람만() {
        Plan plan = service.plan(new Request(" ", null, "someone", List.of(), 0L));
        assertThat(plan.ok()).isFalse();
        assertThat(plan.errors()).hasSize(3)
                .anyMatch(e -> e.contains("등록자"))
                .anyMatch(e -> e.contains("SQL"))
                .anyMatch(e -> e.contains("브랜치"));
        assertThat(service.workers()).containsExactly("jwsung", "ejkim", "jwhan", "chwonhan00");
    }

    @Test
    void 없는_브랜치가_섞여_있으면_전체가_커밋_불가() {
        Plan plan = service.plan(request("SELECT 1;", "ejkim", "develop", "no-such"));
        assertThat(plan.ok()).isFalse();
        assertThat(plan.branches().get(1).errors()).anyMatch(e -> e.contains("찾을 수 없습니다"));
    }

    // ------------------------------------------------------------------ 임시저장

    @Test
    void 임시저장은_revision_으로_다른_사람의_저장을_덮어쓰지_않게_한다() {
        var first = service.saveDraft(new Request("SELECT 1;", "메모", "jwsung", List.of("develop"), 0L));
        assertThat(first.exists()).isTrue();
        assertThat(first.revision()).isPositive();
        assertThat(first.updatedAt()).isEqualTo("2026-11-12 09:30");
        assertThat(service.draft().sql()).isEqualTo("SELECT 1;");

        // 다른 화면이 예전 revision(0) 으로 저장/커밋하려 하면 거부
        assertThatThrownBy(() -> service.saveDraft(new Request("SELECT 9;", "", "ejkim", List.of(), 0L)))
                .isInstanceOf(DraftConflictException.class).hasMessageContaining("jwsung");
        assertThatThrownBy(() -> service.commit(request("SELECT 9;", "ejkim", "develop")))
                .isInstanceOf(DraftConflictException.class);

        // 최신 revision 으로 이어서 저장
        var second = service.saveDraft(new Request("SELECT 1;\nSELECT 2;", "메모", "ejkim", List.of("develop"), first.revision()));
        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(second.lines()).isEqualTo(2);

        // 내용을 모두 지우고 저장하면 임시저장이 없어진다
        assertThat(service.saveDraft(new Request("", "", "", List.of(), second.revision())).exists()).isFalse();
        assertThat(store.readDraft()).isNull();
    }

    // ------------------------------------------------------------------ 커밋

    @Test
    void 여러_브랜치에_같은_파일을_커밋하고_로컬에도_저장하고_임시저장을_지운다() throws Exception {
        var saved = service.saveDraft(new Request("CREATE TABLE users (id INT);", "사용자 테이블 추가", "jwhan",
                List.of("develop", "release"), 0L));
        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PRIVATE-TOKEN", "test-token"))
                .andExpect(jsonPath("$.branch").value("develop"))
                .andExpect(jsonPath("$.commit_message").value(BIRD + "" + NEXT + "\n\n등록자 : jwhan\n설명 : 사용자 테이블 추가"))
                .andExpect(jsonPath("$.author_name").doesNotExist())
                .andExpect(jsonPath("$.actions.length()").value(1))
                .andExpect(jsonPath("$.actions[0].action").value("create"))
                .andExpect(jsonPath("$.actions[0].file_path").value(NEXT_PATH))
                .andExpect(jsonPath("$.actions[0].content").value("CREATE TABLE users (id INT);\n"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"aaaaaaaa11111111\",\"short_id\":\"aaaaaaaa\"}"));
        expectCommit("release", "bbbbbbbb22222222");

        var done = service.commit(new Request("CREATE TABLE users (id INT);", "사용자 테이블 추가", "jwhan",
                List.of("develop", "release"), saved.revision()));

        assertThat(done.allSucceeded()).isTrue();
        assertThat(done.results()).extracting(r -> r.branch() + ":" + r.shortSha())
                .containsExactly("develop:aaaaaaaa", "release:bbbbbbbb");
        assertThat(Files.readString(dir.resolve("2026").resolve(NEXT))).isEqualTo("CREATE TABLE users (id INT);\n");
        assertThat(done.draft().exists()).isFalse();
    }

    @Test
    void 검증_오류가_있으면_어느_브랜치에도_커밋_요청을_보내지_않는다() {
        // POST /repository/commits 기대값을 등록하지 않았으므로 호출하면 MockRestServiceServer 가 실패시킨다
        assertThatThrownBy(() -> service.commit(request("SELECT 1;", "", "develop", "release")))
                .isInstanceOf(InvalidDraftException.class)
                .satisfies(e -> assertThat(((InvalidDraftException) e).getPlan().errors()).anyMatch(m -> m.contains("등록자")));
        assertThat(dir.resolve("2026")).doesNotExist();
    }

    @Test
    void 일부_브랜치만_실패하면_실패한_브랜치와_버전을_임시저장에_남긴다() {
        expectCommit("release", "cccccccc33333333");
        server.expect(once(), requestTo(PROJECT + "/repository/commits"))
                .andExpect(jsonPath("$.branch").value("develop"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"You are not allowed to push into this branch\"}"));

        var done = service.commit(request("SELECT 1;", "chwonhan00", "develop", "release"));

        assertThat(done.allSucceeded()).isFalse();
        assertThat(done.results().get(0).success()).isFalse();
        assertThat(done.results().get(0).error()).contains("권한 없음(403)", "Allowed to push",
                "You are not allowed to push into this branch");
        assertThat(done.results().get(1).success()).isTrue();
        assertThat(done.localFile()).endsWith(NEXT);

        // 남은 브랜치에 다시 커밋할 때 같은 파일명이 쓰이도록 버전을 고정한다
        assertThat(done.draft().branches()).containsExactly("develop");
        assertThat(done.draft().pinnedVersion()).isEqualTo("261112.003");
        assertThat(service.expectedFileName(List.of())).isEqualTo(NEXT);
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
