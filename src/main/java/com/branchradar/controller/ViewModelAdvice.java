package com.branchradar.controller;

import com.branchradar.client.GitLabLinks;
import com.branchradar.config.GitLabProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;

/**
 * 모든 화면에서 ${links.branch(...)} 처럼 GitLab 링크 생성기를 쓸 수 있게 한다.
 * (Thymeleaf 3.1 은 href/data 속성에서 @bean 참조를 막기 때문에 모델 속성으로 넘긴다)
 */
@ControllerAdvice
public class ViewModelAdvice {

    private final GitLabLinks links;
    private final GitLabProperties properties;
    private final String teamName;

    public ViewModelAdvice(GitLabLinks links, GitLabProperties properties,
                           @Value("${branch-radar.team-name:}") String teamName) {
        this.links = links;
        this.properties = properties;
        this.teamName = teamName == null ? "" : teamName.strip();
    }

    /** 상단 바에 표시할 팀명 (branch-radar.team-name). 비어 있으면 표시하지 않는다 */
    @ModelAttribute("teamName")
    public String teamName() {
        return teamName;
    }

    @ModelAttribute("links")
    public GitLabLinks links() {
        return links;
    }

    /**
     * 화면 표시용 타임존 (stale-branch.timezone, 기본 Asia/Seoul).
     * GitLab 응답 시각은 UTC 로 읽히므로 템플릿에서 x.atZoneSameInstant(zone) 으로 바꿔 표시한다.
     */
    @ModelAttribute("zone")
    public ZoneId zone() {
        return properties.staleBranch().zoneId();
    }
}
