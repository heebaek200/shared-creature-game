package com.team.sharedcreature.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** OAuth 상태 전환 후에도 기존 만료 시각과 소비 시각을 보존하는지 검증합니다. */
class OauthLoginAttemptStateTest {

    @Test
    void 소비_완료_시_소비_시각을_기록하고_최초_만료_시각은_유지한다() {
        Instant startedAt = Instant.parse("2026-11-16T00:00:00Z");
        Instant consumedAt = startedAt.plusSeconds(30);
        OauthLoginAttempt attempt = OauthLoginAttempt.start(
                "state-hash", "session-hash", SocialProvider.GOOGLE, OauthLoginIntent.LOGIN,
                null, "DEFAULT", startedAt
        );

        // 생성 직후에는 대기 상태이며 유효 시간은 기존처럼 10분입니다.
        assertThat(attempt.getStatus()).isEqualTo(OauthLoginAttemptStatus.PENDING);
        assertThat(attempt.getConsumedAt()).isNull();
        assertThat(attempt.getExpiresAt()).isEqualTo(startedAt.plusSeconds(600));

        attempt.consume(consumedAt);

        // 소비 상태로 바뀌어도 생성 시각과 만료 시각은 변경되지 않습니다.
        assertThat(attempt.getStatus()).isEqualTo(OauthLoginAttemptStatus.CONSUMED);
        // 소비 후에도 로그인 목적과 이동 경로는 유지합니다.
        assertThat(attempt.getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(attempt.getIntent()).isEqualTo(OauthLoginIntent.LOGIN);
        assertThat(attempt.getReturnRoute()).isEqualTo("DEFAULT");
        assertThat(attempt.getConsumedAt()).isEqualTo(consumedAt);
        assertThat(attempt.getCreatedAt()).isEqualTo(startedAt);
        assertThat(attempt.getExpiresAt()).isEqualTo(startedAt.plusSeconds(600));
    }
}
