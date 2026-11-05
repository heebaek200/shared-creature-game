package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.exception.RestApiExceptionHandler;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.GroupQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 그룹 구성원 목록의 쿼리 파라미터 검증을 MVC 수준에서 확인합니다.
 *
 * 실제 인증·DB Filter는 제외하고 Controller의 제약조건과 오류 변환을 검증합니다.
 * @Min, @Max 위반이 공통 400 응답으로 변환되는지도 확인합니다.
 */
class GroupControllerValidationTest {

    private MockMvc mockMvc;
    private GroupQueryService groupQueryService;
    private LocalValidatorFactoryBean validator;

    /**
     * 인증 사용자와 MVC 검증 환경을 구성합니다.
     *
     * @AuthenticationPrincipal 인자를 실제 SecurityContext에서 해석하도록 설정합니다.
     * 실제 DB 연동 대신 Mockito Service를 사용합니다.
     */
    @BeforeEach
    void setUp() {
        groupQueryService = mock(GroupQueryService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // 유효한 GROUP_MEMBER 인증 객체를 요청 스레드에 설정합니다.
        var principal = new LoginUser(1L, "검증회원", Set.of("MEMBER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "N/A", List.of())
        );

        // Spring MVC 내장 메서드 파라미터 검증과 공통 예외 처리를 사용합니다.
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GroupController(mock(GroupMembershipService.class), groupQueryService)
                )
                .setValidator(validator)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new RestApiExceptionHandler(
                        new ErrorResponseFactory(Clock.systemUTC())
                ))
                .build();
    }

    /**
     * 테스트가 사용한 SecurityContext와 Validator 자원을 정리합니다.
     *
     * 다른 테스트로 인증 상태가 누출되지 않도록 합니다.
     * Bean Validation 리소스도 테스트 단위로 종료합니다.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.destroy();
    }

    /**
     * 허용 범위를 벗어난 페이지 파라미터를 HTTP 400으로 거부하는지 검증합니다.
     *
     * page는 0 이상, size는 1~50만 허용합니다.
     * 유효성 검사 실패 시 조회 Service에 진입하지 않아야 합니다.
     *
     * @param page 요청 페이지 번호
     * @param size 요청 페이지 크기
     */
    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 51"})
    void 잘못된_페이징_요청은_400으로_차단한다(int page, int size) throws Exception {
        // 각 경계 위반을 Controller의 실제 GET 경로로 전달합니다.
        mockMvc.perform(get("/api/v1/groups/me/members")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));

        // 잘못된 요청은 DB 조회까지 도달해서는 안 됩니다.
        verifyNoInteractions(groupQueryService);
    }

    /**
     * 쿼리 파라미터를 생략하면 0페이지와 20건을 사용하는지 확인합니다.
     *
     * 오류 테스트뿐 아니라 정상 요청이 서비스로 전달되는지도 검증합니다.
     * 인증 사용자 ID가 @AuthenticationPrincipal에서 올바르게 전달되어야 합니다.
     */
    @Test
    void 페이징_기본값은_0페이지_20건이다() throws Exception {
        var emptyPage = Page.<GroupMemberResponse>empty(PageRequest.of(0, 20));
        when(groupQueryService.listMyGroupMembers(1L, 0, 20)).thenReturn(emptyPage);

        // 인자를 생략하고 호출했을 때 기본값을 사용하는지 확인합니다.
        mockMvc.perform(get("/api/v1/groups/me/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20));

        verify(groupQueryService).listMyGroupMembers(1L, 0, 20);
    }
}
