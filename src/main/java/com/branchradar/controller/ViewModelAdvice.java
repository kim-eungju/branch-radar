package com.branchradar.controller;

import com.branchradar.client.GitLabLinks;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 화면에서 ${links.branch(...)} 처럼 GitLab 링크 생성기를 쓸 수 있게 한다.
 * (Thymeleaf 3.1 은 href/data 속성에서 @bean 참조를 막기 때문에 모델 속성으로 넘긴다)
 */
@ControllerAdvice
public class ViewModelAdvice {

    private final GitLabLinks links;

    public ViewModelAdvice(GitLabLinks links) {
        this.links = links;
    }

    @ModelAttribute("links")
    public GitLabLinks links() {
        return links;
    }
}
