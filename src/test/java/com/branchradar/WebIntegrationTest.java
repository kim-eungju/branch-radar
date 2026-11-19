package com.branchradar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가짜 GitLab 서버(JDK HttpServer)에 붙여 화면 렌더링까지 확인한다.
 * develop: V1, V2 / feature/x: V1, V2, V3(최신) / release/1.0: V1, V2(내용 다름 → 충돌)
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebIntegrationTest {

    static final HttpServer GITLAB = startFakeGitLab();
    static final String P = "/api/v4/projects/group%2Fdemo";
    static final Path LOCAL_DIR = tempDir();
    /** JSON 본문에서 새 이모지(U+1F426)는 서러게이트 쌍 이스케이프로 전송된다. 뒤에 공백 한 칸 */
    static final String BIRD = "\\" + "uD83D" + "\\" + "uDC26" + " ";

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("branch-radar-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("branch-radar.gitlab.base-url", () -> "http://localhost:" + GITLAB.getAddress().getPort());
        r.add("branch-radar.gitlab.project-id", () -> "group/demo");
        r.add("branch-radar.gitlab.token", () -> "dummy");
        r.add("branch-radar.flyway.path", () -> "db/migration");
        r.add("branch-radar.flyway.local-dir", LOCAL_DIR::toString);
        r.add("branch-radar.flyway.sync-on-startup", () -> "false");
        r.add("branch-radar.team-name", () -> "개발5팀");
        r.add("branch-radar.flyway.workers", () -> "jwsung,ejkim,jwhan,chwonhan00");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @AfterAll
    static void stop() {
        GITLAB.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 대시보드() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("demo"),
                        // 상단 바: Branch Radar 팀명 / GitLab 프로젝트 이름
                        matchesRegex("(?s).*Branch Radar\\s*</a>\\s*(<!--.*?-->\\s*)?<span class=\"br-team\">개발5팀</span>.*class=\"br-crumb\">demo<.*"),
                        containsString("<title>대시보드 · Branch Radar</title>"),
                        containsString("V3"),                                     // 전체 최신 Flyway
                        containsString("V3__c.sql"),
                        containsString("뒤처짐"),
                        containsString("충돌 위험"),
                        containsString("v2.0.0"),                                 // 최신 Tag
                        containsString("Tag 없음"),                               // release 에는 태그 없음
                        containsString("/-/tree/feature/x"),                      // 브랜치 링크
                        containsString("/-/tags/v2.0.0"),
                        containsString("2026-11-01 09:00"),                       // 태그 생성 시각(UTC 00:00) → 한국시간
                        containsString("장기 미갱신"),                            // release: 2026-08-20 → 77일
                        containsString("77일"),
                        containsString("data-activity=\"STALE\""),                // feature/x: 35일
                        not(containsString("notice-title\">GitLab 연결 실패")))))
                // 커밋 후 뒤로 가기로 돌아와도 브라우저가 예전 화면을 재사용하지 않게 한다
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void 기준_브랜치_변경() throws Exception {
        mvc.perform(get("/").param("base", "release/1.0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"release/1.0\" selected=\"selected\">")));
    }

    @Test
    void 비교_화면() throws Exception {
        mvc.perform(get("/compare").param("a", "feature/x").param("b", "release/1.0"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("브랜치에 없는 항목"),
                        containsString("V3__c.sql"),
                        containsString("같은 파일명이지만 SQL 내용이 다름"),
                        containsString("Flyway checksum 다름"),
                        containsString("only in feature"),
                        containsString("이력상 누락 후보"),
                        // 브랜치 선택은 검색되는 select
                        containsString("<select id=\"branchA\" name=\"a\" class=\"form-select\" data-searchable>"),
                        // Git 커밋 비교 탭이 먼저, 기본으로 열림
                        matchesRegex("(?s).*nav-link active\" data-bs-toggle=\"tab\" data-bs-target=\"#tabCommits\".*data-bs-target=\"#tabFlyway\".*"),
                        // "A 에 있고 B 에 없는 커밋" 대신 실제 브랜치 이름
                        containsString("<span class=\"bn bn-a\">feature/x</span> 에 있고 <span class=\"bn bn-b\">release/1.0</span> 에 없는 커밋"),
                        containsString("<span class=\"bn bn-b\">release/1.0</span> 에 있고 <span class=\"bn bn-a\">feature/x</span> 에 없는 커밋"),
                        not(containsString("A 에 있고 B 에 없는")))));
    }

    @Test
    void 커밋_내역_화면() throws Exception {
        mvc.perform(get("/commits").param("branch", "feature/x"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("&lt;b&gt;feature commit&lt;/b&gt;"),           // 제목은 이스케이프
                        containsString("shafeat"),
                        containsString("kim"),
                        containsString("2026-10-01 09:00"),
                        containsString(">merge<"),
                        containsString("page=2"),
                        not(containsString("older commit")))));
        mvc.perform(get("/commits").param("branch", "feature/x").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("older commit"), containsString("page=1"))));
        // 검색: 메시지(2페이지까지 넘겨서 찾음), 작성자(대소문자 무시), SHA 앞부분
        mvc.perform(get("/commits").param("branch", "feature/x").param("q", "OLDER"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("older commit"),
                        not(containsString("feature commit")),
                        containsString("최근 커밋 2개 검색"),
                        containsString("검색 해제"))));
        mvc.perform(get("/commits").param("branch", "feature/x").param("q", "KIM"))
                .andExpect(content().string(allOf(containsString("feature commit"), not(containsString("older commit")))));
        mvc.perform(get("/commits").param("branch", "feature/x").param("q", "old0"))
                .andExpect(content().string(allOf(containsString("older commit"), not(containsString("feature commit")))));
        mvc.perform(get("/commits").param("branch", "feature/x").param("q", "없는검색어"))
                .andExpect(content().string(containsString("검색 결과가 없습니다.")));
        // 대시보드의 브랜치명은 커밋 내역 화면으로 연결된다
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("/commits?branch=feature/x")));
    }

    @Test
    void SQL_미리보기_API() throws Exception {
        mvc.perform(get("/api/sql").param("ref", "sha-feature").param("path", "db/migration/V3__c.sql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<script>alert(1)</script> SELECT 3;"));
        mvc.perform(get("/api/sql").param("ref", "sha-feature").param("path", "src/main/resources/application.yml"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GitLab_장애시_500_대신_오류화면() throws Exception {
        mvc.perform(get("/compare").param("a", "develop").param("b", "no-such-branch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("찾을 수 없음(404)")));
    }

    /** 임시저장 상태를 공유하므로 Flyway 관리 흐름은 한 테스트에서 순서대로 확인한다. 오늘 = 2026-11-05 */
    @Test
    void Flyway_묶음_등록_커밋_보관() throws Exception {
        COMMIT_BODIES.clear();
        String file = "V261105.001__VADA.sql";

        // 화면: develop 의 Flyway 목록, 작업자 4명, 예정 파일명, 기준 브랜치만 기본 선택, 빈 묶음
        mvc.perform(get("/flyway"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("이번 묶음"),
                        containsString("V2__b.sql"),
                        containsString("V1__a.sql"),
                        not(containsString("V3__c.sql")),                          // feature/x 에만 있는 파일
                        containsString("<option value=\"jwsung\">"),
                        containsString("<option value=\"chwonhan00\">"),
                        containsString(file),
                        containsString("value=\"develop\" checked=\"checked\""),
                        not(containsString("value=\"feature/x\" checked=\"checked\"")),
                        // 커밋할 브랜치는 Flyway 상태별 카드: feature/x 최신, develop 뒤처짐, release/1.0 충돌 위험
                        matchesRegex("(?s).*fw-branch-card st-ok.*value=\"feature/x\".*fw-branch-card st-warn"
                                + ".*value=\"develop\".*fw-branch-card st-danger.*value=\"release/1.0\".*"),
                        not(containsString("fw-branch-card st-neutral")),          // 파일 없음·조회 불가 브랜치가 없으면 기타 카드 없음
                        containsString("아직 모인 조각이 없습니다."))));

        // 조각 등록 → 묶음과 대시보드에 표시 (설명은 이스케이프)
        mvc.perform(post("/api/flyway/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"jwhan\",\"description\":\"<b>테스트 설명</b>\",\"sql\":\"SELECT 42;\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").value(1));
        // 작업자를 고르지 않으면 등록 불가
        mvc.perform(post("/api/flyway/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"\",\"description\":\"x\",\"sql\":\"SELECT 1;\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("작업자")));
        // 다른 사람이 조각을 하나 더 올렸다가 삭제 → 내용은 남고 이력에 기록된다
        mvc.perform(post("/api/flyway/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"jwsung\",\"description\":\"지울 조각\",\"sql\":\"DELETE FROM t;\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/flyway/posts/2/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"ejkim\",\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").value(1));
        // 이미 삭제된 조각을 예전 revision 으로 다시 건드리면 409
        mvc.perform(post("/api/flyway/posts/2/update").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"jwhan\",\"description\":\"x\",\"sql\":\"SELECT 1;\",\"revision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("ejkim")));
        long revision = Long.parseLong(mvc.perform(get("/api/flyway/board"))
                // 묶음의 revision 은 첫 필드다 (조각마다 있는 revision 과 헷갈리지 않게 처음 것만)
                .andReturn().getResponse().getContentAsString().replaceAll("(?s)^.*?\"revision\":(\\d+).*$", "$1"));

        mvc.perform(get("/flyway"))
                .andExpect(content().string(allOf(
                        containsString("SELECT 42;"),
                        containsString("&lt;b&gt;테스트 설명&lt;/b&gt;"),
                        containsString("-- [1/1] 등록자 : jwhan"),                  // 합친 파일 미리보기 (삭제된 조각 제외)
                        // 탭: 조각 1 / 삭제된 조각 1 / 변경 이력 3
                        matchesRegex("(?s).*data-tab=\"posts\".*?class=\"count\">1<.*data-tab=\"deleted\".*?class=\"count\">1<"
                                + ".*data-tab=\"log\".*?class=\"count\">3<.*"),
                        containsString("DELETE FROM t;"),                          // 삭제된 조각의 SQL 도 남아 있음
                        containsString("이전 내용"))));
        mvc.perform(get("/"))
                .andExpect(content().string(allOf(
                        containsString("draft-panel"),
                        containsString(file),
                        containsString("SELECT 42;"),
                        containsString("1개"))));

        // 예전 화면(revision 0)에서 커밋하면 409
        mvc.perform(post("/api/flyway/commit").contentType(MediaType.APPLICATION_JSON)
                        .content(commitJson("jwhan", 0, "develop")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("ejkim 이(가) 2026-11-05 12:00 에 삭제")));

        // 검증: 작업자를 고르지 않으면 커밋 불가
        mvc.perform(post("/api/flyway/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(commitJson("", revision, "develop")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.fileName").value(file));
        mvc.perform(post("/api/flyway/commit").contentType(MediaType.APPLICATION_JSON)
                        .content(commitJson("", revision, "develop")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.plan.errors[0]").value(containsString("작업자")));
        // JSON 이 아닌 본문(다른 사이트의 form 전송)은 받지 않는다
        mvc.perform(post("/api/flyway/commit").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("actor", "jwhan").param("branches", "develop"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/flyway/posts").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("actor", "jwhan").param("description", "x").param("sql", "DROP TABLE x;"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(COMMIT_BODIES).isEmpty();

        // 커밋: 두 브랜치에 같은 파일, 로컬 폴더에도 저장, 묶음은 보관 후 비움
        mvc.perform(post("/api/flyway/commit").contentType(MediaType.APPLICATION_JSON)
                        .content(commitJson("ejkim", revision, "develop", "feature/x")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSucceeded").value(true))
                .andExpect(jsonPath("$.filePath").value("db/migration/" + file))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].sha").value("c0ffee0000000001"))
                .andExpect(jsonPath("$.archive").value("V261105.001__VADA"));

        assertThat(COMMIT_BODIES).hasSize(2);
        assertThat(COMMIT_BODIES.get(0))
                .contains("\"branch\":\"develop\"")
                .contains("\"file_path\":\"db/migration/" + file + "\"")
                .contains("\"action\":\"create\"")
                .contains(BIRD + "" + file + "\\n\\n등록자 : jwhan\\n커밋 : ejkim\\n설명 : <b>테스트 설명</b>");
        assertThat(COMMIT_BODIES.get(1)).contains("\"branch\":\"feature/x\"");
        assertThat(LOCAL_DIR.resolve(file)).content(StandardCharsets.UTF_8)
                .contains("-- [1/1] 등록자 : jwhan | 등록 : 2026-11-05 12:00", "-- 설명 : <b>테스트 설명</b>")
                .endsWith("SELECT 42;\n");
        mvc.perform(get("/"))
                .andExpect(content().string(not(containsString("draft-panel"))));

        // 지난 묶음: 목록과 상세 (읽기 전용)
        mvc.perform(get("/flyway"))
                .andExpect(content().string(allOf(
                        containsString("아직 모인 조각이 없습니다."),
                        containsString("/flyway/history/V261105.001__VADA"))));
        mvc.perform(get("/flyway/history/V261105.001__VADA"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("SELECT 42;"),
                        containsString("&lt;b&gt;테스트 설명&lt;/b&gt;"),
                        containsString("develop, feature/x"),
                        containsString("삭제된 조각 1개"),
                        containsString("DELETE FROM t;"),
                        containsString("커밋"))));
        mvc.perform(get("/flyway/history/no-such"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("찾을 수 없습니다")));
    }

    private static String commitJson(String actor, long revision, String... branches) {
        return "{\"actor\":\"" + actor + "\",\"revision\":" + revision
                + ",\"branches\":[\"" + String.join("\",\"", branches) + "\"]}";
    }

    // ------------------------------------------------------------------ fake GitLab

    /** 가짜 GitLab 이 받은 커밋 요청 본문 */
    static final List<String> COMMIT_BODIES = new CopyOnWriteArrayList<>();

    private static String branchJson(String name, String sha, String date) {
        return "{\"name\":\"" + name + "\",\"commit\":{\"id\":\"" + sha + "\",\"short_id\":\"" + sha
                + "\",\"title\":\"t\",\"committed_date\":\"" + date + "\"}}";
    }

    private static final String DEVELOP = branchJson("develop", "sha-develop", "2026-11-04T10:00:00+09:00");
    private static final String FEATURE = branchJson("feature/x", "sha-feature", "2026-10-01T10:00:00+09:00");
    private static final String RELEASE = branchJson("release/1.0", "sha-release", "2026-08-20T10:00:00+09:00");

    private static String tree(String... files) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.length; i++) {
            String[] p = files[i].split(":");
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(p[1]).append("\",\"name\":\"").append(p[0])
                    .append("\",\"type\":\"blob\",\"path\":\"db/migration/").append(p[0]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static HttpServer startFakeGitLab() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", WebIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String body = null;
        String type = "application/json";

        if (ex.getRequestMethod().equals("POST")) {
            if (path.equals(P + "/repository/commits")) {
                COMMIT_BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                body = "{\"id\":\"c0ffee0000000001\",\"short_id\":\"c0ffee00\",\"title\":\"t\"}";
            }
        } else if (path.equals(P + "/repository/commits")) {
            if (query.contains("ref_name=feature%2Fx")) {
                if (query.contains("&page=1")) {
                    ex.getResponseHeaders().add("X-Next-Page", "2");
                    body = "[{\"id\":\"sha-feature\",\"short_id\":\"shafeat\",\"title\":\"<b>feature commit</b>\","
                            + "\"author_name\":\"kim\",\"committed_date\":\"2026-10-01T09:00:00+09:00\",\"parent_ids\":[\"a\",\"b\"]}]";
                } else {
                    body = "[{\"id\":\"old0001\",\"short_id\":\"old0001\",\"title\":\"older commit\",\"parent_ids\":[\"a\"]}]";
                }
            }
        } else if (path.equals(P)) {
            body = "{\"id\":1,\"name\":\"demo\",\"path_with_namespace\":\"group/demo\",\"web_url\":\"https://gitlab.example.com/group/demo\"}";
        } else if (path.equals(P + "/repository/branches")) {
            body = "[" + DEVELOP + "," + FEATURE + "," + RELEASE + "]";
        } else if (path.equals(P + "/repository/branches/develop")) {
            body = DEVELOP;
        } else if (path.equals(P + "/repository/branches/feature%2Fx")) {
            body = FEATURE;
        } else if (path.equals(P + "/repository/branches/release%2F1.0")) {
            body = RELEASE;
        } else if (path.equals(P + "/repository/tree")) {
            if (query.contains("ref=sha-develop")) body = tree("V1__a.sql:a1", "V2__b.sql:b1");
            else if (query.contains("ref=sha-feature")) body = tree("V1__a.sql:a1", "V2__b.sql:b1", "V3__c.sql:c1");
            else if (query.contains("ref=sha-release")) body = tree("V1__a.sql:a1", "V2__b.sql:b2");
        } else if (path.startsWith(P + "/repository/files/")) {
            type = "text/plain";
            if (path.contains("V3__c.sql")) body = "<script>alert(1)</script> SELECT 3;";
            else if (query.contains("ref=sha-release")) body = "SELECT 2 /* release */;";
            else body = "SELECT 2;";
        } else if (path.equals(P + "/repository/compare")) {
            body = query.startsWith("from=sha-release&to=sha-feature")
                    ? "{\"commits\":[{\"id\":\"f00d0001\",\"short_id\":\"f00d000\",\"title\":\"only in feature\",\"parent_ids\":[\"x\"]}]}"
                    : "{\"commits\":[]}";
        } else if (path.equals(P + "/repository/tags")) {
            body = "[{\"name\":\"v1.0.0\",\"target\":\"sha-develop\",\"commit\":{\"id\":\"sha-develop\"}},"
                    + "{\"name\":\"v2.0.0\",\"target\":\"tagobj\",\"message\":\"rel\",\"created_at\":\"2026-11-01T00:00:00Z\","
                    + "\"commit\":{\"id\":\"sha-feature\"}}]";
        } else if (path.equals(P + "/repository/commits/sha-feature/refs")) {
            body = "[{\"type\":\"branch\",\"name\":\"feature/x\"}]";
        } else if (path.equals(P + "/repository/commits/sha-develop/refs")) {
            body = "[{\"type\":\"branch\",\"name\":\"develop\"},{\"type\":\"branch\",\"name\":\"feature/x\"}]";
        }

        byte[] bytes = (body == null ? "{\"message\":\"404 Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
