package com.team.sharedcreature._core.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST API 경계에서 공통으로 적용할 Web MVC 규칙을 구성합니다.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final StrictQueryParameterInterceptor strictQueryParameterInterceptor;

    /** Query 검증 Interceptor를 주입받습니다. */
    public WebConfiguration(StrictQueryParameterInterceptor strictQueryParameterInterceptor) {
        this.strictQueryParameterInterceptor = strictQueryParameterInterceptor;
    }

    /** 내부 REST API와 OAuth Callback에 엄격한 Query 검증을 적용합니다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(strictQueryParameterInterceptor)
                .addPathPatterns("/api/v1/**", "/oauth2/callback/**");
    }
}
