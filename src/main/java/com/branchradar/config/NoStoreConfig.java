package com.branchradar.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 화면과 API 응답을 브라우저가 보관하지 않게 한다 (Cache-Control: no-store).
 * <p>
 * 이 도구의 화면은 열 때마다 GitLab 을 조회한 결과다. 브라우저가 예전 화면을 보관해 두면,
 * 커밋한 뒤 '뒤로 가기' 로 대시보드에 돌아왔을 때 커밋 전 상태가 그대로 보인다.
 * 정적 파일(css, js, 폰트)은 컨트롤러를 거치지 않으므로 영향을 받지 않는다.
 */
@Configuration
public class NoStoreConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod) {
                    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                }
                return true;
            }
        });
    }
}
