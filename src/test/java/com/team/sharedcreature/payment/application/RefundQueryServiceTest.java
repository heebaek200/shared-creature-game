package com.team.sharedcreature.payment.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.domain.OwnedItemStatus;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.payment.api.AdminRefundResponse;
import com.team.sharedcreature.payment.api.RefundResponse;
import com.team.sharedcreature.payment.domain.PaymentTransaction;
import com.team.sharedcreature.payment.domain.PaymentTransactionStatus;
import com.team.sharedcreature.payment.domain.PurchaseStatus;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.domain.RefundStatus;
import com.team.sharedcreature.payment.infrastructure.PaymentTransactionRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 환불 Enum 전환 이후 목록 필터·페이지·응답 문자열 계약을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class RefundQueryServiceTest {
    @Mock private RefundRequestRepository refunds;
    @Mock private OwnedItemRepository items;
    @Mock private PaymentTransactionRepository transactions;
    private RefundQueryService service;

    @BeforeEach
    void 준비() {
        service = new RefundQueryService(refunds, items, transactions);
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void 내목록과_관리자목록은_Enum으로_조회하고_상태문자열을_반환한다(RefundStatus status) {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        RefundRequest refund = RefundRequest.create(item.getPurchase(), item.getUser(), "조회 테스트", CREATED_AT);
        ReflectionTestUtils.setField(refund, "id", 20L);
        if (status == RefundStatus.REJECTED) {
            refund.reject(user(9L), "거절", NOW);
        } else if (status != RefundStatus.REQUESTED) {
            refund.claimCancellation(user(9L), "승인", NOW);
            switch (status) {
                case CANCEL_UNKNOWN -> refund.markCancellationUnknown(NOW);
                case COMPLETED -> refund.completeCancellation(NOW, NOW);
                case FAILED -> refund.failCancellation(NOW);
                default -> { }
            }
        }
        when(refunds.findMyRefunds(eq(1L), eq(status), any()))
                .thenReturn(new PageImpl<>(List.of(refund)));
        when(refunds.findAdminRefunds(eq(status), any()))
                .thenReturn(new PageImpl<>(List.of(refund)));

        Page<RefundResponse> mine = service.listMyRefunds(1L, 0, 20, status);
        Page<RefundResponse> admin = service.listAdminRefunds(0, 20, status);

        assertThat(mine.getContent().getFirst().status()).isEqualTo(status);
        assertThat(mine.getContent().getFirst().requestedByUserId()).isEqualTo(1L);
        assertThat(admin.getContent().getFirst().status()).isEqualTo(status);
        assertThat(mine.getTotalElements()).isEqualTo(1);
        verifyNoInteractions(items, transactions);
    }

    @Test
    void 상태_생략은_내목록과_관리자목록의_전체조회로_유지한다() {
        when(refunds.findMyRefunds(eq(1L), isNull(), any())).thenReturn(Page.empty());
        when(refunds.findAdminRefunds(isNull(), any())).thenReturn(Page.empty());

        assertThat(service.listMyRefunds(1L, 0, 20, null)).isEmpty();
        assertThat(service.listAdminRefunds(0, 20, null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,51"})
    void 페이지_범위오류는_기존대로_조회전에_거절한다(int page, int size) {
        assertFieldError(() -> service.listMyRefunds(1L, page, size, null));
        assertFieldError(() -> service.listAdminRefunds(page, size, null));
        verifyNoInteractions(refunds, items, transactions);
    }

    /** 입력 검증이 일반 서버 오류로 바뀌지 않도록 오류 코드를 확인합니다. */
    private void assertFieldError(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
    }

    @Test
    void adminRefundDetailSerializesNestedStatusesAsStringsAndReadsThemBack() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        RefundRequest refund = RefundRequest.create(item.getPurchase(), item.getUser(), "test refund", CREATED_AT);
        PaymentTransaction cancellation = PaymentTransaction.cancellationRequested(item.getPurchase(), NOW);
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(AdminRefundResponse.from(refund, item, cancellation));
        JsonNode tree = mapper.readTree(json);
        assertThat(tree.at("/purchase/status").asString()).isEqualTo("PAID");
        assertThat(tree.at("/ownedItem/status").asString()).isEqualTo("OWNED_UNUSED");
        assertThat(tree.at("/latestCancellation/status").asString()).isEqualTo("REQUESTED");

        AdminRefundResponse decoded = mapper.readValue(json, AdminRefundResponse.class);
        assertThat(decoded.purchase().status()).isEqualTo(PurchaseStatus.PAID);
        assertThat(decoded.ownedItem().status()).isEqualTo(OwnedItemStatus.OWNED_UNUSED);
        assertThat(decoded.latestCancellation().status()).isEqualTo(PaymentTransactionStatus.REQUESTED);

        String jsonWithoutOptionalDetails = mapper.writeValueAsString(AdminRefundResponse.from(refund, null, null));
        assertThat(jsonWithoutOptionalDetails).contains("\"ownedItem\":null", "\"latestCancellation\":null");
    }
}
