package com.branchradar.client;

import com.branchradar.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitLabClientTest {

    /** 프로젝트 경로 "group/my project" 는 하나의 경로 변수로 인코딩되어야 한다 */
    static final String PROJECT = TestData.BASE_URL + "/api/v4/projects/group%2Fmy%20project";

    MockRestServiceServer server;
    GitLabClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitLabClient(TestData.props(3), builder.build());
    }

    private static HttpHeaders nextPage(String next) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Next-Page", next);
        return h;
    }

    @Test
    void 페이지네이션을_끝까지_따라가고_토큰_헤더를_보낸다() {
        server.expect(requestTo(PROJECT + "/repository/branches?per_page=100&page=1"))
                .andExpect(header("PRIVATE-TOKEN", "test-token"))
                .andRespond(withSuccess("[{\"name\":\"a\",\"commit\":{\"id\":\"1\"}}]", MediaType.APPLICATION_JSON)
                        .headers(nextPage("2")));
        server.expect(requestTo(PROJECT + "/repository/branches?per_page=100&page=2"))
                .andRespond(withSuccess("[{\"name\":\"b\",\"commit\":{\"id\":\"2\"}}]", MediaType.APPLICATION_JSON)
                        .headers(nextPage("")));

        var page = client.listBranches();

        assertThat(page.complete()).isTrue();
        assertThat(page.items()).extracting(GitLabClient.Branch::name).containsExactly("a", "b");
        server.verify();
    }

    @Test
    void max_pages_를_넘으면_불완전으로_표시한다() {
        for (int i = 1; i <= 3; i++) {
            server.expect(requestTo(PROJECT + "/repository/branches?per_page=100&page=" + i))
                    .andRespond(withSuccess("[{\"name\":\"b" + i + "\",\"commit\":{\"id\":\"x\"}}]", MediaType.APPLICATION_JSON)
                            .headers(nextPage(String.valueOf(i + 1))));
        }
        var page = client.listBranches();
        assertThat(page.complete()).isFalse();
        assertThat(page.items()).hasSize(3);
    }

    @Test
    void 브랜치명과_파일경로의_특수문자를_인코딩한다() {
        server.expect(requestTo(PROJECT + "/repository/branches/feature%2F%ED%95%9C%EA%B8%80%23%2B1"))
                .andRespond(withSuccess("{\"name\":\"feature/한글#+1\",\"commit\":{\"id\":\"1\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROJECT + "/repository/files/db%2Fmigration%2FV1__a%20b.sql/raw?ref=feature%2Fx%26y"))
                .andRespond(withSuccess("SELECT 1;", MediaType.TEXT_PLAIN));

        assertThat(client.getBranch("feature/한글#+1").name()).isEqualTo("feature/한글#+1");
        assertThat(new String(client.getRawFile("db/migration/V1__a b.sql", "feature/x&y"))).isEqualTo("SELECT 1;");
        server.verify();
    }

    @Test
    void Compare_API는_from과_to를_그대로_전달한다() {
        server.expect(requestTo(PROJECT + "/repository/compare?from=release%2F1.0&to=develop&straight=false"))
                .andRespond(withSuccess("{\"commits\":[{\"id\":\"abc\",\"short_id\":\"abc\",\"title\":\"t\"}],"
                        + "\"compare_timeout\":false,\"diffs\":[]}", MediaType.APPLICATION_JSON));
        var res = client.compare("release/1.0", "develop");
        assertThat(res.commits()).extracting(GitLabClient.Commit::id).containsExactly("abc");
        assertThat(res.compareTimeout()).isFalse();
    }

    @Test
    void 오류_상태코드를_사용자_메시지로_변환한다() {
        expectError(HttpStatus.UNAUTHORIZED, "인증 실패");
        expectError(HttpStatus.FORBIDDEN, "권한 없음");
        expectError(HttpStatus.NOT_FOUND, "찾을 수 없음");
        expectError(HttpStatus.TOO_MANY_REQUESTS, "호출 한도 초과");
        expectError(HttpStatus.BAD_GATEWAY, "GitLab 서버 오류");
    }

    private void expectError(HttpStatus status, String text) {
        server.reset();
        server.expect(requestTo(PROJECT)).andRespond(withStatus(status));
        assertThatThrownBy(() -> client.getProject())
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining(text)
                .satisfies(e -> assertThat(((GitLabException) e).getStatus()).isEqualTo(status.value()));
    }

    @Test
    void GitLab_웹_링크는_경로_조각별로_인코딩한다() {
        GitLabLinks links = new GitLabLinks();
        String web = "https://gitlab.example.com/group/project/";
        assertThat(links.branch(web, "feature/a#1 b"))
                .isEqualTo("https://gitlab.example.com/group/project/-/tree/feature/a%231%20b");
        assertThat(links.file(web, "abc123", "db/migration/V1__한.sql"))
                .isEqualTo("https://gitlab.example.com/group/project/-/blob/abc123/db/migration/V1__%ED%95%9C.sql");
        assertThat(links.tag(web, "v1.0.0+build"))
                .isEqualTo("https://gitlab.example.com/group/project/-/tags/v1.0.0+build");
    }
}
