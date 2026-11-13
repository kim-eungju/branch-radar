package com.branchradar.client;

import com.branchradar.config.GitLabProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab REST API v4 클라이언트. 조회가 대부분이고, 쓰기는 {@link #createFileCommit} 하나뿐이다.
 * <p>
 * - 모든 경로 변수(프로젝트 ID, 브랜치명, 파일 경로)와 쿼리 값은 직접 퍼센트 인코딩해서 URI 를 만든다.
 *   ('/' 도 %2F 로 인코딩해야 GitLab 이 하나의 값으로 인식한다.)
 * - 목록 API 는 X-Next-Page 헤더를 따라 끝까지 조회하고, max-pages 를 넘으면 complete=false 로 반환한다.
 */
@Component
public class GitLabClient {

    private static final int PER_PAGE = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final GitLabProperties.Gitlab config;

    @Autowired
    public GitLabClient(GitLabProperties properties, RestClient.Builder builder) {
        this(properties, builder
                .requestFactory(timeoutRequestFactory(properties.gitlab()))
                .build());
    }

    /** 테스트에서 MockRestServiceServer 가 연결된 RestClient 를 주입할 때 사용 */
    public GitLabClient(GitLabProperties properties, RestClient restClient) {
        this.config = properties.gitlab();
        this.restClient = restClient;
    }

    private static JdkClientHttpRequestFactory timeoutRequestFactory(GitLabProperties.Gitlab cfg) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(cfg.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(cfg.readTimeout());
        return factory;
    }

    // ---------------------------------------------------------------- API

    public Project getProject() {
        return get(projectUrl(""), Project.class, "프로젝트 " + config.projectId()).getBody();
    }

    public Paged<Branch> listBranches() {
        return getAllPages(projectUrl("/repository/branches?per_page=" + PER_PAGE),
                new ParameterizedTypeReference<>() {}, "브랜치 목록");
    }

    public Branch getBranch(String name) {
        return get(projectUrl("/repository/branches/" + enc(name)), Branch.class, "브랜치 '" + name + "'").getBody();
    }

    /**
     * 디렉터리 트리(하위 폴더 포함). 디렉터리가 없으면 GitLab 이 404 를 주므로 호출자가 구분해서 처리한다.
     */
    public Paged<TreeEntry> listTree(String path, String ref) {
        String url = projectUrl("/repository/tree?recursive=true&per_page=" + PER_PAGE
                + "&path=" + enc(path) + "&ref=" + enc(ref));
        return getAllPages(url, new ParameterizedTypeReference<>() {}, "디렉터리 '" + path + "' @ " + ref);
    }

    public byte[] getRawFile(String path, String ref) {
        String url = projectUrl("/repository/files/" + enc(path) + "/raw?ref=" + enc(ref));
        byte[] body = get(url, byte[].class, "파일 '" + path + "' @ " + ref).getBody();
        return body == null ? new byte[0] : body;
    }

    /**
     * GitLab Compare API.
     * 응답의 commits 는 "to 에서 도달 가능하지만 from 에서는 도달 불가능한 커밋"(git log from..to) 이다.
     * 따라서 "A 에는 있고 B 에는 없는 커밋" 을 얻으려면 from=B, to=A 로 호출해야 한다.
     * straight 파라미터는 diff 계산 방식(merge-base 사용 여부)에만 영향을 주므로 기본값(false, merge-base)을 쓴다.
     */
    public CompareResponse compare(String from, String to) {
        String url = projectUrl("/repository/compare?from=" + enc(from) + "&to=" + enc(to) + "&straight=false");
        return get(url, CompareResponse.class, "비교 " + from + " → " + to).getBody();
    }

    public Paged<Tag> listTags() {
        return getAllPages(projectUrl("/repository/tags?per_page=" + PER_PAGE),
                new ParameterizedTypeReference<>() {}, "태그 목록");
    }

    /**
     * 해당 커밋을 포함하는(= 커밋이 브랜치의 조상인) 브랜치 목록.
     * GitLab 이 git 의 "branch --contains" 와 같은 방식으로 계산한다.
     */
    public Paged<Ref> commitBranches(String sha) {
        return getAllPages(projectUrl("/repository/commits/" + enc(sha) + "/refs?type=branch&per_page=" + PER_PAGE),
                new ParameterizedTypeReference<>() {}, "커밋 " + sha + " 포함 브랜치");
    }

    /**
     * 이 클라이언트의 유일한 쓰기 호출: 브랜치에 새 파일 1개를 추가하는 커밋을 만든다 (Commits API, action=create).
     * 같은 경로에 파일이 이미 있으면 GitLab 이 400 으로 거부하므로 기존 파일을 덮어쓰지 않는다.
     *
     * @param authorName  null 이면 토큰 계정 이름으로 기록된다
     * @param authorEmail null 이면 토큰 계정 이메일로 기록된다
     */
    public Commit createFileCommit(String branch, String filePath, String content, String message,
                                   String authorName, String authorEmail) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action", "create");
        action.put("file_path", filePath);
        action.put("content", content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        body.put("commit_message", message);
        if (authorName != null) body.put("author_name", authorName);
        if (authorEmail != null) body.put("author_email", authorEmail);
        body.put("actions", List.of(action));

        String what = "커밋 생성 '" + filePath + "' @ " + branch;
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(URI.create(projectUrl("/repository/commits")))
                    .contentType(MediaType.APPLICATION_JSON);
            if (config.hasToken()) {
                spec = spec.header("PRIVATE-TOKEN", config.token());
            }
            return spec.body(body).retrieve().toEntity(Commit.class).getBody();
        } catch (RestClientResponseException e) {
            throw GitLabException.fromWriteStatus(e.getStatusCode().value(), what,
                    errorDetail(e.getResponseBodyAsString(StandardCharsets.UTF_8)), e);
        } catch (RestClientException e) {
            throw translate(e, what);
        }
    }

    /** GitLab 오류 응답({"message": ...} 또는 {"error": ...})에서 사유만 뽑는다. */
    static String errorDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        String detail;
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode node = root.hasNonNull("message") ? root.get("message") : root.get("error");
            if (node == null || node.isNull()) return null;
            detail = node.isTextual() ? node.asText() : node.toString();
        } catch (JsonProcessingException e) {
            detail = responseBody.strip();
        }
        return detail.length() > 300 ? detail.substring(0, 300) + "…" : detail;
    }

    // ---------------------------------------------------------------- helpers

    String projectUrl(String suffix) {
        String base = config.baseUrl() == null ? "" : config.baseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api/v4/projects/" + enc(config.projectId()) + suffix;
    }

    /** 경로 변수/쿼리 값 인코딩: '/' 포함 모든 예약 문자를 퍼센트 인코딩한다. */
    static String enc(String value) {
        return UriUtils.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private <T> Paged<T> getAllPages(String firstUrl, ParameterizedTypeReference<List<T>> type, String what) {
        List<T> all = new ArrayList<>();
        String sep = firstUrl.contains("?") ? "&" : "?";
        int page = 1;
        while (true) {
            ResponseEntity<List<T>> res = get(firstUrl + sep + "page=" + page, type, what);
            if (res.getBody() != null) all.addAll(res.getBody());
            String next = res.getHeaders().getFirst("X-Next-Page");
            if (next == null || next.isBlank()) {
                return new Paged<>(all, true);
            }
            if (page >= config.maxPages()) {
                return new Paged<>(all, false);
            }
            page = Integer.parseInt(next.trim());
        }
    }

    private <T> ResponseEntity<T> get(String url, Class<T> type, String what) {
        try {
            return request(url).retrieve().toEntity(type);
        } catch (RestClientException e) {
            throw translate(e, what);
        }
    }

    private <T> ResponseEntity<T> get(String url, ParameterizedTypeReference<T> type, String what) {
        try {
            return request(url).retrieve().toEntity(type);
        } catch (RestClientException e) {
            throw translate(e, what);
        }
    }

    private RestClient.RequestHeadersSpec<?> request(String url) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(URI.create(url));
        if (config.hasToken()) {
            spec = spec.header("PRIVATE-TOKEN", config.token());
        }
        return spec;
    }

    private static GitLabException translate(RestClientException e, String what) {
        if (e instanceof RestClientResponseException re) {
            return GitLabException.fromStatus(re.getStatusCode().value(), what, e);
        }
        if (e instanceof ResourceAccessException) {
            return new GitLabException(0, "GitLab 연결 실패 또는 시간 초과: " + what
                    + " (base-url, 네트워크, 타임아웃 설정을 확인하세요)", e);
        }
        return new GitLabException(0, "GitLab 응답 처리 실패: " + what + " — " + e.getMessage(), e);
    }

    // ---------------------------------------------------------------- response types

    /** 페이지네이션 결과. complete=false 면 max-pages 제한으로 일부만 조회된 것이다. */
    public record Paged<T>(List<T> items, boolean complete) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(
            long id,
            String name,
            @JsonProperty("path_with_namespace") String pathWithNamespace,
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("default_branch") String defaultBranch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(
            String name,
            Commit commit,
            @JsonProperty("default") boolean isDefault,
            @JsonProperty("protected") boolean isProtected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(
            String id,
            @JsonProperty("short_id") String shortId,
            String title,
            String message,
            @JsonProperty("author_name") String authorName,
            @JsonProperty("author_email") String authorEmail,
            @JsonProperty("authored_date") OffsetDateTime authoredDate,
            @JsonProperty("committed_date") OffsetDateTime committedDate,
            @JsonProperty("web_url") String webUrl,
            @JsonProperty("parent_ids") List<String> parentIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TreeEntry(String id, String name, String type, String path) {
    }

    /**
     * 태그. target 은 annotated 태그면 태그 객체 SHA, lightweight 면 커밋 SHA 이다.
     * commit 은 두 경우 모두 태그가 가리키는 실제 커밋이다.
     * created_at 은 annotated 태그에만 있다 (lightweight 는 null).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(
            String name,
            String message,
            String target,
            Commit commit,
            @JsonProperty("created_at") OffsetDateTime createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String type, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompareResponse(
            List<Commit> commits,
            @JsonProperty("compare_timeout") boolean compareTimeout,
            @JsonProperty("compare_same_ref") boolean compareSameRef,
            @JsonProperty("web_url") String webUrl) {
    }
}
