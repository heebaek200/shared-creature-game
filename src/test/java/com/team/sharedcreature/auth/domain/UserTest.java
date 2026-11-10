package com.team.sharedcreature.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    /**
     * 기존 회원의 로그인 성공 시 인증 활동 시각이 갱신되는지 확인합니다.
     *
     * 회원가입 시점과 로그인 시점을 분리해 검증합니다.
     * 로그인 성공 시각과 마지막 활동 시각이 동일하게 변경되어야 합니다.
     */
    @Test
    void 기존_회원_로그인_성공_시_활동_시각을_갱신한다() {
        Instant registeredAt = Instant.parse("2026-11-01T00:00:00Z");
        Instant loginAt = Instant.parse("2026-11-04T03:00:00Z");

        // 가입 시각으로 회원을 생성합니다.
        User user = User.register("USER", registeredAt);

        // 재로그인을 수행합니다.
        user.recordLogin(loginAt);

        // 로그인 및 활동 시각이 갱신되었는지 검증합니다.
        assertThat(user.getLastLoginAt()).isEqualTo(loginAt);
        assertThat(user.getLastActiveAt()).isEqualTo(loginAt);
        assertThat(user.getUpdatedAt()).isEqualTo(loginAt);
        assertThat(user.getCreatedAt()).isEqualTo(registeredAt);
    }
}