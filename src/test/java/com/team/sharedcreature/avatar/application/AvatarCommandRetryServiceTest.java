package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.api.AvatarResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 아바타 명령에 한정된 재시도 횟수와 업무 오류의 즉시 반환을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class AvatarCommandRetryServiceTest {

    @Mock private AvatarService avatarService;
    private AvatarCommandRetryService service;
    private final AvatarResponse response = new AvatarResponse(
            1L, new AvatarResponse.Equipments(null, null, null, null));

    @BeforeEach
    void setUp() {
        service = new AvatarCommandRetryService(avatarService);
    }

    @Test
    void 첫_시도에_성공하면_한번만_실행한다() {
        when(avatarService.equipAvatarItem(1L, "HEAD", 10L, true)).thenReturn(response);

        assertThat(service.equipAvatarItem(1L, "HEAD", 10L, true)).isSameAs(response);

        verify(avatarService).equipAvatarItem(1L, "HEAD", 10L, true);
    }

    @Test
    void 잠금과_버전_충돌_후_세번째_시도가_성공하면_그_결과를_반환한다() {
        when(avatarService.equipAvatarItem(1L, "HEAD", 10L, true))
                .thenThrow(new PessimisticLockingFailureException("잠금 충돌"))
                .thenThrow(new OptimisticLockingFailureException("버전 충돌"))
                .thenReturn(response);

        assertThat(service.equipAvatarItem(1L, "HEAD", 10L, true)).isSameAs(response);

        verify(avatarService, times(3)).equipAvatarItem(1L, "HEAD", 10L, true);
    }

    @Test
    void 재시도_한도에_도달하면_아바타_업무_충돌로_변환한다() {
        when(avatarService.equipAvatarItem(1L, "HEAD", 10L, true))
                .thenThrow(new PessimisticLockingFailureException("잠금 충돌"));

        assertThatThrownBy(() -> service.equipAvatarItem(1L, "HEAD", 10L, true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));

        verify(avatarService, times(3)).equipAvatarItem(1L, "HEAD", 10L, true);
    }

    @Test
    void 환불_진행_같은_업무_충돌은_같은_오류_코드여도_재시도하지_않는다() {
        var failure = new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "환불 진행 중");
        when(avatarService.equipAvatarItem(1L, "HEAD", 10L, true)).thenThrow(failure);

        assertThatThrownBy(() -> service.equipAvatarItem(1L, "HEAD", 10L, true))
                .isSameAs(failure);

        verify(avatarService).equipAvatarItem(1L, "HEAD", 10L, true);
    }

    @Test
    void 최초_사용_동의_누락은_재시도하지_않는다() {
        var failure = new BusinessException(ErrorCode.FIRST_USE_CONFIRMATION_REQUIRED);
        when(avatarService.equipAvatarItem(1L, "HEAD", 10L, false)).thenThrow(failure);

        assertThatThrownBy(() -> service.equipAvatarItem(1L, "HEAD", 10L, false))
                .isSameAs(failure);

        verify(avatarService).equipAvatarItem(1L, "HEAD", 10L, false);
    }

    @Test
    void 해제에도_동일한_재시도_정책을_적용한다() {
        when(avatarService.unequipAvatarItem(1L, "HEAD"))
                .thenThrow(new OptimisticLockingFailureException("버전 충돌"))
                .thenReturn(response);

        assertThat(service.unequipAvatarItem(1L, "HEAD")).isSameAs(response);

        verify(avatarService, times(2)).unequipAvatarItem(1L, "HEAD");
    }
}
