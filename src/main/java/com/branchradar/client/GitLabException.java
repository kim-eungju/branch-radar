package com.branchradar.client;

/**
 * GitLab API 호출 실패. message 는 화면에 그대로 보여줄 수 있는 문장이다.
 * status 가 0 이면 HTTP 응답을 받지 못한 경우(연결 실패, 타임아웃 등)다.
 */
public class GitLabException extends RuntimeException {

    private final int status;

    public GitLabException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    static GitLabException fromStatus(int status, String what, Throwable cause) {
        String msg;
        if (status == 401) {
            msg = "인증 실패(401): GitLab 토큰이 없거나 만료/폐기되었습니다. GITLAB_TOKEN 을 확인하세요.";
        } else if (status == 403) {
            msg = "권한 없음(403): 토큰에 read_api 권한이 있는지, 프로젝트 접근 권한(Reporter 이상)이 있는지 확인하세요.";
        } else if (status == 404) {
            msg = "찾을 수 없음(404): " + what + " — 프로젝트 ID/경로, 브랜치명, 파일 경로를 확인하세요. (권한이 없을 때도 404가 반환될 수 있습니다)";
        } else if (status == 429) {
            msg = "GitLab API 호출 한도 초과(429). 잠시 후 다시 시도하세요.";
        } else if (status >= 500) {
            msg = "GitLab 서버 오류(" + status + "): " + what;
        } else {
            msg = "GitLab API 오류(" + status + "): " + what;
        }
        return new GitLabException(status, msg, cause);
    }

    /**
     * 쓰기(커밋) 요청 실패. GitLab 이 응답 본문에 적어 준 거부 사유(detail)를 함께 보여 준다.
     * 400 은 대개 "같은 경로의 파일이 이미 있음", 403 은 권한/보호 브랜치 문제다.
     */
    static GitLabException fromWriteStatus(int status, String what, String detail, Throwable cause) {
        String suffix = detail == null || detail.isBlank() ? "" : " — GitLab 응답: " + detail;
        if (status == 403) {
            return new GitLabException(status, "권한 없음(403): " + what
                    + " — 토큰에 api(쓰기) 권한이 있는지, 계정 역할이 Developer 이상인지, "
                    + "보호 브랜치라면 'Allowed to push' 대상인지 확인하세요." + suffix, cause);
        }
        if (status == 400 || status == 409 || status == 422) {
            return new GitLabException(status, "GitLab 이 커밋을 거부했습니다(" + status + "): " + what + suffix, cause);
        }
        GitLabException base = fromStatus(status, what, cause);
        return suffix.isEmpty() ? base : new GitLabException(status, base.getMessage() + suffix, cause);
    }
}
