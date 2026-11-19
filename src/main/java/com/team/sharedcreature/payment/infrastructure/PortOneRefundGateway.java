package com.team.sharedcreature.payment.infrastructure;

import com.team.sharedcreature.payment.application.RefundGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PortOne V2의 전체 결제 취소를 호출합니다.
 * 자동 재시도 없이 한 번 호출하고 결과를 내부 모델로 변환합니다.
 */
@Component
public class PortOneRefundGateway implements RefundGateway {

    private final RestClient restClient;
    private final PortOneProperties properties;

    /**
     * 기존 결제 조회와 분리된 환불 전용 클라이언트를 주입받습니다.
     *
     * @param restClient 환불 전용 HTTP 클라이언트
     * @param properties PortOne 접속 설정
     */
    public PortOneRefundGateway(
            @Qualifier("refundRestClient") RestClient restClient,
            PortOneProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * DB 트랜잭션 밖에서 전체 취소를 한 번 요청합니다.
     */
    @Override
    public CancellationResult cancelFully(
            String paymentId,
            String reason,
            String idempotencyKey
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "PG 취소는 DB 트랜잭션 밖에서 호출해야 합니다."
            );
        }

        validateInput(paymentId, reason, idempotencyKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);

        if (properties.storeId() != null && !properties.storeId().isBlank()) {
            body.put("storeId", properties.storeId());
        }

        // amount를 생략하여 전체 취소를 요청합니다.
        try {
            ResponseEntity<JsonNode> response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/payments/{paymentId}/cancel")
                            .build(paymentId))
                    // PortOne의 Structured Field 문자열 형식을 적용합니다.
                    .header("Idempotency-Key", "\"" + idempotencyKey + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(JsonNode.class);

            int httpStatus = response.getStatusCode().value();

            if (!response.getStatusCode().is2xxSuccessful()) {
                return unknown(paymentId, httpStatus);
            }

            return mapResult(paymentId, response.getBody(), httpStatus);
        } catch (RestClientResponseException exception) {
            // 이미 취소됨·인증 오류·서버 오류 등을 취소 실패로 단정하지 않습니다.
            return unknown(paymentId, exception.getStatusCode().value());
        } catch (RestClientException exception) {
            // Timeout·통신 장애·응답 변환 실패는 최신 조회로 확인해야 합니다.
            return unknown(paymentId, null);
        }
    }

    /** PG 호출 전 내부 입력과 Header에 넣을 UUID 형식을 검사합니다. */
    private void validateInput(
            String paymentId,
            String reason,
            String idempotencyKey
    ) {
        if (paymentId == null || paymentId.isBlank()
                || reason == null || reason.isBlank()
                || reason.length() > 500) {
            throw new IllegalArgumentException("PG 취소 호출 정보가 유효하지 않습니다.");
        }

        if (idempotencyKey == null
                || !UUID.fromString(idempotencyKey).toString()
                .equalsIgnoreCase(idempotencyKey)) {
            throw new IllegalArgumentException("PG 취소 멱등 키는 표준 UUID여야 합니다.");
        }
    }

    /** HTTP 성공 여부와 별개로 cancellation의 실제 상태를 분류합니다. */
    private CancellationResult mapResult(
            String paymentId,
            JsonNode body,
            int httpStatus
    ) {
        if (body == null || !body.isObject()) {
            return unknown(paymentId, httpStatus);
        }

        JsonNode cancellation = body.path("cancellation");

        if (!cancellation.isObject()) {
            return unknown(paymentId, httpStatus);
        }

        String status = text(cancellation, "status");
        String transactionId = text(cancellation, "id");
        Long amount = amount(cancellation.path("totalAmount"));
        Instant cancelledAt = instant(cancellation, "cancelledAt");

        if (transactionId == null || transactionId.isBlank()
                || transactionId.length() > 255) {
            return unknown(paymentId, httpStatus);
        }

        Outcome outcome = switch (status == null ? "" : status) {
            case "SUCCEEDED" ->
                    amount != null && cancelledAt != null
                            ? Outcome.SUCCESS
                            : Outcome.UNKNOWN;
            case "FAILED" -> Outcome.DEFINITIVE_FAILURE;
            case "REQUESTED" -> Outcome.PENDING;
            default -> Outcome.UNKNOWN;
        };

        return new CancellationResult(
                outcome,
                paymentId,
                transactionId,
                amount,
                outcome == Outcome.SUCCESS ? cancelledAt : null,
                httpStatus,
                status != null && status.length() <= 100 ? status : null
        );
    }

    /** 금액을 확인할 수 없으면 0원으로 추정하지 않고 null로 유지합니다. */
    private Long amount(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            return null;
        }

        long amount = value.longValue();
        return amount >= 0 ? amount : null;
    }

    /** JSON 문자열 필드만 읽습니다. */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    /** 잘못된 시각을 서버 시각으로 대체하지 않습니다. */
    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);

        if (value == null) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /** 성공·실패를 확정할 수 없는 결과를 생성합니다. */
    private CancellationResult unknown(String paymentId, Integer httpStatus) {
        return new CancellationResult(
                Outcome.UNKNOWN,
                paymentId,
                null,
                null,
                null,
                httpStatus,
                null
        );
    }
}