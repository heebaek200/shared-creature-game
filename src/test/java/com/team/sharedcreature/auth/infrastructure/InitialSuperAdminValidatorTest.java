package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.GlobalRole;
import com.team.sharedcreature.auth.domain.SocialAccount;
import com.team.sharedcreature.auth.domain.SocialProvider;
import com.team.sharedcreature.auth.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 최초 관리자 설정의 공급자 검증과 Enum 조회 사이의 호환성을 확인합니다. */
class InitialSuperAdminValidatorTest {

    @Test
    void 소문자_Google_설정도_기존처럼_허용하고_Enum으로_조회한다() {
        UserRepository users = mock(UserRepository.class);
        SocialAccountRepository accounts = mock(SocialAccountRepository.class);
        var validator = new InitialSuperAdminValidator(
                users, accounts, new InitialSuperAdminProperties("google", "provider-user-id")
        );

        // 활성 전체 관리자가 없고 아직 가입하지 않은 공급자 계정입니다.
        when(users.existsByGlobalRoleAndStatus(GlobalRole.SUPER_ADMIN, UserStatus.ACTIVE))
                .thenReturn(false);
        when(accounts.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "provider-user-id"))
                .thenReturn(Optional.empty());

        validator.run(null);

        verify(accounts).findByProviderAndProviderUserId(SocialProvider.GOOGLE, "provider-user-id");
    }

    @Test
    void Enum에_있어도_현재_지원하지_않는_Naver_설정은_거절한다() {
        UserRepository users = mock(UserRepository.class);
        SocialAccountRepository accounts = mock(SocialAccountRepository.class);
        var validator = new InitialSuperAdminValidator(
                users, accounts, new InitialSuperAdminProperties("NAVER", "provider-user-id")
        );

        // Enum 정의만으로 실제 지원 공급자가 늘어나면 안 됩니다.
        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("현재 최초 전체 관리자 공급자는 GOOGLE만 지원합니다.");
        verifyNoInteractions(accounts);
    }

    @Test
    void 최초_관리자_대상이_이미_가입한_경우_기동_설정_오류로_거절한다() {
        UserRepository users = mock(UserRepository.class);
        SocialAccountRepository accounts = mock(SocialAccountRepository.class);
        var validator = new InitialSuperAdminValidator(
                users, accounts, new InitialSuperAdminProperties("GOOGLE", "provider-user-id")
        );
        when(accounts.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "provider-user-id"))
                .thenReturn(Optional.of(mock(SocialAccount.class)));

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("최초 전체 관리자 대상이 이미 일반 사용자로 등록되어 있습니다.");
    }
}
