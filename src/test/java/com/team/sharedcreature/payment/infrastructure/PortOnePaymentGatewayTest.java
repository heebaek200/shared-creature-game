package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.application.PaymentGatewayException;
import com.team.sharedcreature.payment.application.PaymentSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PortOnePaymentGatewayTest {

    private static final String PAYMENT_ID = "d4a7bba1-5180-49ef-8d61-94f18f7c7197";

    private MockRestServiceServer server;
    private PortOnePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        PortOneProperties properties = new PortOneProperties(
                "store-test",
                "channel-test",
                "api-secret",
                "webhook-secret",
                "https://api.portone.io"
        );
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.apiBaseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PortOnePaymentGateway(properties, builder.build());
    }

    @Test
    void 결제_조회_응답을_내부_Snapshot으로_변환한다() {
        server.expect(once(), requestTo("https://api.portone.io/payments/" + PAYMENT_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "PortOne api-secret"))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "storeId": "store-test",
                          "transactionId": "tx-1",
                          "status": "PAID",
                          "amount": {"total": 1000},
                          "currency": "KRW",
                          "method": {"type": "EASY_PAY", "provider": "KAKAOPAY"},
                          "channel": {"type": "TEST"},
                          "paidAt": "2026-11-09T08:00:00Z",
                          "cancellations": []
                        }
                        """.formatted(PAYMENT_ID), MediaType.APPLICATION_JSON));

        PaymentSnapshot result = gateway.getPayment(PAYMENT_ID);

        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(result.storeId()).isEqualTo("store-test");
        assertThat(result.transactionId()).isEqualTo("tx-1");
        assertThat(result.status()).isEqualTo(PaymentSnapshot.Status.PAID);
        assertThat(result.totalAmount()).isEqualTo(1000L);
        assertThat(result.currency()).isEqualTo("KRW");
        assertThat(result.payMethod()).isEqualTo("EASY_PAY");
        assertThat(result.easyPayProvider()).isEqualTo("KAKAOPAY");
        assertThat(result.channelType()).isEqualTo("TEST");
        assertThat(result.paidAt()).isEqualTo(Instant.parse("2026-11-09T08:00:00Z"));
        assertThat(result.cancellations()).isEmpty();
        server.verify();
    }

    @Test
    void 존재하지_않는_결제는_NOT_FOUND로_분류한다() {
        server.expect(once(), requestTo("https://api.portone.io/payments/" + PAYMENT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gateway.getPayment(PAYMENT_ID))
                .isInstanceOfSatisfying(PaymentGatewayException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(PaymentGatewayException.Kind.NOT_FOUND));
        server.verify();
    }

    @Test
    void PortOne_PENDING_상태를_내부_대기상태로_유지한다() {
        server.expect(once(), requestTo("https://api.portone.io/payments/" + PAYMENT_ID))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "storeId": "store-test",
                          "transactionId": "tx-pending",
                          "status": "PENDING",
                          "amount": {"total": 1000},
                          "currency": "KRW"
                        }
                        """.formatted(PAYMENT_ID), MediaType.APPLICATION_JSON));

        PaymentSnapshot result = gateway.getPayment(PAYMENT_ID);

        assertThat(result.status()).isEqualTo(PaymentSnapshot.Status.PENDING);
        server.verify();
    }

    @Test
    void 취소_목록을_환불_복구용_Snapshot으로_변환한다() {
        server.expect(once(), requestTo("https://api.portone.io/payments/" + PAYMENT_ID))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "storeId": "store-test",
                          "transactionId": "tx-1",
                          "status": "PARTIAL_CANCELLED",
                          "amount": {"total": 1000},
                          "currency": "KRW",
                          "cancellations": [{
                            "id": "cancel-1",
                            "status": "SUCCEEDED",
                            "totalAmount": 1000,
                            "requestedAt": "2026-11-09T08:00:00Z",
                            "cancelledAt": "2026-11-09T08:01:00Z"
                          }]
                        }
                        """.formatted(PAYMENT_ID), MediaType.APPLICATION_JSON));

        PaymentSnapshot result = gateway.getPayment(PAYMENT_ID);

        assertThat(result.cancellations()).singleElement().satisfies(cancellation -> {
            assertThat(cancellation.cancellationId()).isEqualTo("cancel-1");
            assertThat(cancellation.status()).isEqualTo("SUCCEEDED");
            assertThat(cancellation.cancelledAmount()).isEqualTo(1000L);
            assertThat(cancellation.requestedAt()).isEqualTo(Instant.parse("2026-11-09T08:00:00Z"));
            assertThat(cancellation.cancelledAt()).isEqualTo(Instant.parse("2026-11-09T08:01:00Z"));
        });
        server.verify();
    }
}
