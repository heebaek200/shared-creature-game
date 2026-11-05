package com.team.sharedcreature.group.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.group.application.GroupMembershipService;
import com.team.sharedcreature.group.application.GroupQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 그룹 탈퇴 Controller의 세션 변경과 API 응답 계약을 검증합니다.
 *
 * 실제 Spring MVC·Security Filter Chain을 실행하지 않는 순수 단위 테스트입니다.
 * CSRF와 경로별 접근 제어는 MockMvc 또는 통합 테스트에서 확인해야 합니다.
 */
class GroupDepartureControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    /**
     * 성공한 탈퇴는 그룹 역할만 세션에서 제거합니다.
     *
     * SUPER_ADMIN 전역 권한과 기존 로그인 세션은 유지합니다.
     * API 응답에는 Service의 탈퇴 결과가 그대로 들어가야 합니다.
     */
    @Test
    void 탈퇴_성공_시_그룹_역할만_제거하고_로그인은_유지한다() {
        GroupMembershipService service = mock(GroupMembershipService.class);
        GroupController controller = new GroupController(service, mock(GroupQueryService.class));
        MockHttpSession session = new MockHttpSession();
        LoginUser loginUser = new LoginUser(1L, "테스트", Set.of("PRIMARY", "SUPER_ADMIN"));
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, loginUser);
        LeaveMyGroupResponse expected = new LeaveMyGroupResponse(
                10L, 1L, NOW, "SELF", NOW.plusSeconds(86_400), false);
        when(service.leaveMyGroup(1L)).thenReturn(expected);

        // Service의 정상 결과 뒤에만 세션 역할을 갱신합니다.
        var response = controller.leaveMyGroup(loginUser, session);
        LoginUser refreshed = (LoginUser) session.getAttribute(
                SessionAuthenticationFilter.LOGIN_USER);
        assertThat(response.data()).isSameAs(expected);
        assertThat(refreshed.id()).isEqualTo(1L);
        assertThat(refreshed.nickname()).isEqualTo("테스트");
        assertThat(refreshed.roles()).containsExactly("SUPER_ADMIN");
        assertThat(session.isInvalid()).isFalse();
        verify(service).leaveMyGroup(1L);
    }

    /**
     * Service가 탈퇴를 거부했다면 세션의 권한 정보를 변경하지 않습니다.
     *
     * 다인 그룹의 PRIMARY는 역할 이전 전 탈퇴할 수 없습니다.
     * 트랜잭션 실패에 대응해 Controller가 세션을 먼저 수정하지 않도록 보장합니다.
     */
    @Test
    void 탈퇴_실패_시_세션_권한을_변경하지_않는다() {
        GroupMembershipService service = mock(GroupMembershipService.class);
        GroupController controller = new GroupController(service, mock(GroupQueryService.class));
        MockHttpSession session = new MockHttpSession();
        LoginUser loginUser = new LoginUser(1L, "테스트", Set.of("PRIMARY"));
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER, loginUser);
        when(service.leaveMyGroup(1L)).thenThrow(
                new BusinessException(ErrorCode.PRIMARY_TRANSFER_REQUIRED));

        // 예외가 발생했다면 기존 로그인 정보를 보존해야 합니다.
        assertThatThrownBy(() -> controller.leaveMyGroup(loginUser, session))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PRIMARY_TRANSFER_REQUIRED"));
        assertThat(session.getAttribute(SessionAuthenticationFilter.LOGIN_USER))
                .isSameAs(loginUser);
    }

    /**
     * API 응답 Record는 확정된 JSON 계약 이름을 사용해야 합니다.
     *
     * record 구성요소 이름이 JavaBean 응답 필드로 직결되는 점을 검사합니다.
     * restrictionUtil 등의 오탈자는 빌드 단계에서 실패로 발견합니다.
     */
    @Test
    void 탈퇴_응답은_restrictionUntil_필드명을_사용한다() {
        // 역직렬화 설정과 관계없이 선언된 Record 필드 이름을 검사합니다.
        Set<String> names = java.util.Arrays.stream(LeaveMyGroupResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(names).contains("restrictionUntil", "groupDeactivated", "endedAt");
        assertThat(names).doesNotContain("restrictionUtil");
    }
}
