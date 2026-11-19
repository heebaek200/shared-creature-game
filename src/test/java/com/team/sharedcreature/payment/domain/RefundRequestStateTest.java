package com.team.sharedcreature.payment.domain;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/** 환불 심사와 복구 시간 경계의 업무 규칙을 검증합니다. */
class RefundRequestStateTest {
    private static final Instant START = Instant.parse("2026-11-13T00:00:00Z");
    private final User reviewer = BeanUtils.instantiateClass(User.class);

    private RefundRequest requested() {
        // 외부 의존성이 없는 엔티티 테스트이므로 실제 객체를 사용합니다.
        return RefundRequest.create(new Purchase(), BeanUtils.instantiateClass(User.class), "  요청 사유  ", START);
    }

    private RefundRequest pending() {
        RefundRequest refund = requested();
        refund.claimCancellation(reviewer, "  승인 사유  ", START);
        return refund;
    }

    @Test
    void 승인하면_사유와_심사정보를_기록하고_완료로_처리하지_않는다() {
        RefundRequest refund = pending();
        assertThat(refund.getRequestReason()).isEqualTo("요청 사유");
        assertThat(refund.getDecisionReason()).isEqualTo("승인 사유");
        assertThat(refund.getReviewedByUser()).isSameAs(reviewer);
        assertThat(refund.getReviewedAt()).isEqualTo(START);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.CANCEL_PENDING);
        assertThat(refund.getCompletedAt()).isNull();
    }

    @Test
    void 승인_후_거절이나_중복_승인은_충돌이다() {
        RefundRequest refund = pending();
        assertConflict(() -> refund.reject(reviewer, "거절", START));
        assertConflict(() -> refund.claimCancellation(reviewer, "승인", START));
    }

    @Test
    void 거절하면_재승인할_수_없고_취소_선점시각은_없다() {
        RefundRequest refund = requested();
        refund.reject(reviewer, "  거절 사유  ", START);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(refund.getDecisionReason()).isEqualTo("거절 사유");
        assertThat(refund.getCancelClaimedAt()).isNull();
        assertConflict(() -> refund.claimCancellation(reviewer, "승인", START));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 빈_사유는_상태를_변경하지_않는다(String reason) {
        RefundRequest refund = requested();
        assertThatThrownBy(() -> refund.claimCancellation(reviewer, reason, START))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getReviewedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 대기상태는_정확히_5분부터_복구한다(boolean manual) {
        RefundRequest refund = pending();
        assertThat(refund.tryClaimRecovery(START.plusSeconds(300).minusNanos(1), manual)).isFalse();
        assertThat(refund.getCancelClaimedAt()).isEqualTo(START);
        assertThat(refund.tryClaimRecovery(START.plusSeconds(300), manual)).isTrue();
        assertThat(refund.getCancelClaimedAt()).isEqualTo(START.plusSeconds(300));
        assertThat(refund.tryClaimRecovery(START.plusSeconds(301), manual)).isFalse();
    }

    @Test
    void 결과불명은_관리자만_5분전에도_복구를_선점한다() {
        RefundRequest refund = pending();
        refund.markCancellationUnknown(START.plusSeconds(1));
        assertThat(refund.tryClaimRecovery(START.plusSeconds(2))).isFalse();
        assertThat(refund.tryClaimRecovery(START.plusSeconds(2), true)).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.CANCEL_UNKNOWN);
        assertThat(refund.getReviewedAt()).isEqualTo(START);
        assertThat(refund.getCompletedAt()).isNull();
    }

    @Test
    void 성공확정_후_늦은_실패와_중복성공은_기존_완료시각을_보존한다() {
        RefundRequest refund = pending();
        Instant pgTime = START.plusSeconds(10);
        assertThat(refund.completeCancellation(pgTime, START.plusSeconds(11))).isTrue();
        assertThat(refund.failCancellation(START.plusSeconds(12))).isFalse();
        assertThat(refund.completeCancellation(START.plusSeconds(20), START.plusSeconds(21))).isFalse();
        assertThat(refund.tryClaimRecovery(START.plusSeconds(600), true)).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isEqualTo(pgTime);
        assertThat(refund.getUpdatedAt()).isEqualTo(START.plusSeconds(11));
    }

    @Test
    void 확정실패_후_늦은_성공을_덮어쓰지_않는다() {
        RefundRequest refund = pending();
        assertThat(refund.failCancellation(START.plusSeconds(10))).isTrue();
        assertThat(refund.completeCancellation(START.plusSeconds(11), START.plusSeconds(12))).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getCompletedAt()).isNull();
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }
}
