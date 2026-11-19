package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature.avatar.domain.OwnedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.test.util.ReflectionTestUtils;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** PG 호출 Enum 전환 후 준비·완료 구분과 최초 호출 결과·멱등 키 보존을 검증합니다. */
class PgApiAttemptStateTest {
    private RefundRequest refund;
    private PgApiAttempt attempt;

    @BeforeEach
    void 준비() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        refund = RefundRequest.create(item.getPurchase(), item.getUser(), "요청", CREATED_AT);
        ReflectionTestUtils.setField(refund, "id", 30L);
        refund.claimCancellation(user(9L), "승인", CREATED_AT);
        attempt = PgApiAttempt.prepareCancellation(refund, 1, CREATED_AT);
        ReflectionTestUtils.setField(attempt, "id", 40L);
    }

    @ParameterizedTest
    @EnumSource(value = PgApiResultType.class, names = "STARTED", mode = EnumSource.Mode.EXCLUDE)
    void 준비이력에는_확인된_네가지_결과를_기록할수있다(PgApiResultType result) {
        assertThat(attempt.getOperation()).isEqualTo(PgApiOperation.CANCELLATION);
        assertThat(attempt.getResultType()).isEqualTo(PgApiResultType.STARTED);
        assertThat(attempt.getCompletedAt()).isNull();

        assertThat(attempt.complete(result, 200, "PG", "{}", null, NOW)).isTrue();

        assertThat(attempt.getResultType()).isEqualTo(result);
        assertThat(attempt.getCompletedAt()).isEqualTo(NOW);
        assertThat(attempt.getStartedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = PgApiResultType.class, names = "STARTED")
    void 준비상태나_null은_완료결과로_기록하지_않는다(PgApiResultType result) {
        assertThatThrownBy(() -> attempt.complete(result, 200, null, "{}", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempt.getResultType()).isEqualTo(PgApiResultType.STARTED);
        assertThat(attempt.getCompletedAt()).isNull();
    }

    @Test
    void 최초_호출결과는_나중에_도착한_다른결과로_덮어쓰지_않는다() {
        attempt.complete(PgApiResultType.UNKNOWN, null, "FIRST", "{\"first\":true}", null, NOW);

        assertThat(attempt.complete(PgApiResultType.SUCCESS, 200, "SECOND", "{}", null, NOW.plusSeconds(1)))
                .isFalse();
        assertThat(attempt.getResultType()).isEqualTo(PgApiResultType.UNKNOWN);
        assertThat(attempt.getProviderResultCode()).isEqualTo("FIRST");
        assertThat(attempt.getResponseSummary()).isEqualTo("{\"first\":true}");
        assertThat(attempt.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void 복구조회는_취소작업과_구분하고_최초멱등키를_유지한다() {
        PgApiAttempt lookup = PgApiAttempt.prepareCancellationLookup(refund, attempt, 2, NOW);

        assertThat(lookup.getOperation()).isEqualTo(PgApiOperation.CANCELLATION_LOOKUP);
        assertThat(lookup.getResultType()).isEqualTo(PgApiResultType.STARTED);
        assertThat(lookup.getIdempotencyKey()).isEqualTo(attempt.getIdempotencyKey());
        assertThat(lookup.getAttemptNumber()).isEqualTo(2);
        assertThat(attempt.getOperation()).isEqualTo(PgApiOperation.CANCELLATION);
        assertThat(attempt.getCompletedAt()).isNull();
    }

    @Test
    void 조회이력을_최초취소이력으로_잘못연결하면_거절한다() {
        PgApiAttempt lookup = PgApiAttempt.prepareCancellationLookup(refund, attempt, 2, NOW);
        ReflectionTestUtils.setField(lookup, "id", 41L);

        assertThatThrownBy(() -> PgApiAttempt.prepareCancellationLookup(refund, lookup, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
