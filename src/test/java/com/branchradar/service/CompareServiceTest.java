package com.branchradar.service;

import com.branchradar.TestData;
import com.branchradar.client.GitLabClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.branchradar.TestData.branch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CompareServiceTest {

    static final String PROJECT = TestData.BASE_URL + "/api/v4/projects/group%2Fmy%20project";

    private static GitLabClient.Commit commit(String id, String title, String message) {
        return new GitLabClient.Commit(id, id.substring(0, 7), title, message, "dev", "dev@example.com",
                null, null, null, List.of("p"));
    }

    @Test
    void A에_있고_B에_없는_커밋은_from_B_to_A_로_조회한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabClient client = new GitLabClient(TestData.props(), builder.build());
        CompareService service = new CompareService(client, null, null);

        // a = develop(sha-develop), b = release(sha-release)
        server.expect(requestTo(PROJECT + "/repository/compare?from=sha-release&to=sha-develop&straight=false"))
                .andRespond(withSuccess("{\"commits\":[{\"id\":\"aaaaaaa111\",\"short_id\":\"aaaaaaa\",\"title\":\"only in develop\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROJECT + "/repository/compare?from=sha-develop&to=sha-release&straight=false"))
                .andRespond(withSuccess("{\"commits\":[{\"id\":\"bbbbbbb222\",\"short_id\":\"bbbbbbb\",\"title\":\"only in release\"}],"
                        + "\"compare_timeout\":true}", MediaType.APPLICATION_JSON));

        var result = service.compareCommits(branch("develop"), branch("release"));

        assertThat(result.aNotB()).extracting(c -> c.title()).containsExactly("only in develop");
        assertThat(result.bNotA()).extracting(c -> c.title()).containsExactly("only in release");
        assertThat(result.incomplete()).isTrue();
        server.verify();
    }

    @Test
    void 커밋_비교_API_실패시_오류로_표시하고_예외를_던지지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompareService service = new CompareService(new GitLabClient(TestData.props(), builder.build()), null, null);
        server.expect(requestTo(PROJECT + "/repository/compare?from=sha-b&to=sha-a&straight=false"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var result = service.compareCommits(branch("a"), branch("b"));
        assertThat(result.error()).contains("503");
        assertThat(result.incomplete()).isTrue();
    }

    @Test
    void cherry_pick_기록이_있으면_힌트만_붙이고_후보로_유지한다() {
        var original = commit("1111111aaaa", "fix bug", "fix bug");
        var picked = commit("2222222bbbb", "fix bug", "fix bug\n\n(cherry picked from commit 1111111aaaa)");
        var unrelated = commit("3333333cccc", "other", "other");

        var items = CompareService.toItems(List.of(original, unrelated), List.of(picked));

        assertThat(items).hasSize(2);
        assertThat(items.stream().filter(i -> i.sha().equals("1111111aaaa")).findFirst().orElseThrow().hint())
                .contains("cherry-pick 기록");
        assertThat(items.stream().filter(i -> i.sha().equals("3333333cccc")).findFirst().orElseThrow().hint()).isNull();
    }

    @Test
    void 제목과_작성자가_같으면_가능성으로만_표시한다() {
        var a = commit("1111111aaaa", "add column", "add column");
        var b = commit("2222222bbbb", "add column", "add column");
        var items = CompareService.toItems(List.of(a), List.of(b));
        assertThat(items.get(0).hint()).contains("가능성");
    }

    @Test
    void 누락_후보를_cherry_pick_흔적_유무로_나누고_작성자_목록을_만든다() {
        var original = commit("1111111aaaa", "fix bug", "fix bug");
        var picked = commit("2222222bbbb", "fix bug", "fix bug\n\n(cherry picked from commit 1111111aaaa)");
        var unrelated = commit("3333333cccc", "other", "other");

        var aNotB = CompareService.toItems(List.of(original, unrelated), List.of(picked));
        var bNotA = CompareService.toItems(List.of(picked), List.of(original, unrelated));
        var cc = new com.branchradar.dto.CompareResult.CommitCompare(aNotB, bNotA, false, null, null);

        assertThat(cc.aNotBNoPick()).extracting(i -> i.sha()).containsExactly("3333333cccc");
        assertThat(cc.aNotBMaybePicked()).extracting(i -> i.sha()).containsExactly("1111111aaaa");
        assertThat(cc.bNotANoPick()).isEmpty();
        assertThat(cc.bNotAMaybePicked()).extracting(i -> i.sha()).containsExactly("2222222bbbb");
        assertThat(cc.authors()).containsExactly("dev");
    }
}
