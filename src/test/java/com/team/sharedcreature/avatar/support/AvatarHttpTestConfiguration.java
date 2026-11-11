package com.team.sharedcreature.avatar.support;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.AvatarApiExceptionHandler;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature._core.security.SecurityConfiguration;
import com.team.sharedcreature._core.security.SecurityErrorWriter;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature._core.web.RequestIdFilter;
import com.team.sharedcreature._core.web.StrictQueryParameterInterceptor;
import com.team.sharedcreature._core.web.WebConfiguration;
import com.team.sharedcreature.auth.api.CsrfController;
import com.team.sharedcreature.auth.application.UserActivityService;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.avatar.api.AvatarController;
import com.team.sharedcreature.avatar.api.ShopController;
import com.team.sharedcreature.avatar.application.AvatarCommandRetryService;
import com.team.sharedcreature.avatar.application.AvatarService;
import com.team.sharedcreature.avatar.application.ShopQueryService;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/** 실제 보안·MVC 설정을 사용하되 DB 경계만 대체하는 이슈 20 전용 테스트 구성입니다. */
@TestConfiguration
@EnableWebMvc
@EnableWebSecurity
@Import({SecurityConfiguration.class, WebConfiguration.class, CsrfController.class})
public class AvatarHttpTestConfiguration implements WebMvcConfigurer {

    /** 테스트에서도 Controller의 @Validated 검증을 실제 프록시로 실행합니다. */
    @Bean
    static MethodValidationPostProcessor methodValidationPostProcessor() {
        var processor = new MethodValidationPostProcessor();
        processor.setProxyTargetClass(true);
        return processor;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapper objectMapper() {
        // 운영 환경의 미등록 필드 거부만 재현하며 전역 타입 강제 변환 규칙은 추가하지 않습니다.
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new JacksonJsonHttpMessageConverter(objectMapper()));
    }

    @Bean
    ErrorResponseFactory errorResponseFactory(Clock clock) {
        return new ErrorResponseFactory(clock);
    }

    @Bean
    SecurityErrorWriter securityErrorWriter(ObjectMapper mapper, ErrorResponseFactory factory) {
        return new SecurityErrorWriter(mapper, factory);
    }

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    SessionAuthenticationFilter sessionAuthenticationFilter(
            UserRepository users, GroupMembershipRepository memberships) {
        return new SessionAuthenticationFilter(users, memberships, mock(UserActivityService.class));
    }

    @Bean
    StrictQueryParameterInterceptor strictQueryParameterInterceptor() {
        return new StrictQueryParameterInterceptor();
    }

    @Bean
    UserRepository userRepository() {
        return mock(UserRepository.class);
    }

    @Bean
    GroupMembershipRepository groupMembershipRepository() {
        return mock(GroupMembershipRepository.class);
    }

    @Bean
    AvatarService avatarService() {
        return mock(AvatarService.class);
    }

    @Bean
    AvatarCommandRetryService avatarCommandRetryService() {
        return mock(AvatarCommandRetryService.class);
    }

    @Bean
    ShopQueryService shopQueryService() {
        return mock(ShopQueryService.class);
    }

    @Bean
    AvatarController avatarController(AvatarService query, AvatarCommandRetryService commands) {
        return new AvatarController(query, commands);
    }

    @Bean
    ShopController shopController(ShopQueryService service) {
        return new ShopController(service);
    }

    @Bean
    AvatarApiExceptionHandler avatarApiExceptionHandler(ErrorResponseFactory factory) {
        return new AvatarApiExceptionHandler(factory);
    }

    @Bean
    RestApiExceptionHandler restApiExceptionHandler(ErrorResponseFactory factory) {
        return new RestApiExceptionHandler(factory);
    }

    /** 운영 API를 수정하지 않고 새 예외 처리기의 적용 범위 밖 동작을 검사합니다. */
    @TestComponent
    @RestController
    public static class IsolationProbeController {

        @GetMapping("/api/v1/issue20-test/other")
        public Map<String, Object> getOther(@RequestParam(name = "page", required = false) String page) {
            return Map.of("ok", true);
        }

        @PostMapping("/api/v1/issue20-test/other")
        public OtherRequest postOther(@RequestBody OtherRequest request) {
            return request;
        }
    }

    /** 장착 DTO의 타입 검증이 다른 DTO에 전파되지 않는지 검사할 대조군입니다. */
    public record OtherRequest(Long id, boolean enabled) {
    }
}
