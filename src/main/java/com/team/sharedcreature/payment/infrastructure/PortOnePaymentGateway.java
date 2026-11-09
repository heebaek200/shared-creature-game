package com.team.sharedcreature.payment.infrastructure;

import tools.jackson.databind.JsonNode;
import com.team.sharedcreature.payment.application.PaymentGateway;
import com.team.sharedcreature.payment.application.PaymentGatewayException;
import com.team.sharedcreature.payment.application.PaymentSnapshot;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Spring RestClient로 PortOne V2 결제 단건 조회를 수행합니다. */
@Component
@EnableConfigurationProperties(PortOneProperties.class)
public class PortOnePaymentGateway implements PaymentGateway {

    private final PortOneProperties properties;
    private final RestClient restClient;

    /** 환경별 PortOne Endpoint와 인증 Header를 구성합니다. */
    public PortOnePaymentGateway(PortOneProperties properties, RestClient.Builder builder) {
        this(properties, builder.baseUrl(properties.apiBaseUrl()).build());
    }

    PortOnePaymentGateway(PortOneProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public PaymentSnapshot getPayment(String paymentId) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/payments/{paymentId}").build(paymentId))
                    .header(HttpHeaders.AUTHORIZATION, "PortOne " + properties.apiSecret())
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                throw new PaymentGatewayException(
                        PaymentGatewayException.Kind.INVALID_RESPONSE,
                        "PortOne 결제 응답이 비어 있습니다.",
                        null
                );
            }
            return map(body);
        } catch (RestClientResponseException exception) {
            PaymentGatewayException.Kind kind = exception.getStatusCode().value() == 404
                    ? PaymentGatewayException.Kind.NOT_FOUND
                    : PaymentGatewayException.Kind.UNAVAILABLE;
            throw new PaymentGatewayException(kind, "PortOne 결제 조회에 실패했습니다.", exception);
        } catch (RestClientException exception) {
            throw new PaymentGatewayException(
                    PaymentGatewayException.Kind.UNAVAILABLE,
                    "PortOne 결제 조회 결과를 확인할 수 없습니다.",
                    exception
            );
        }
    }

    private PaymentSnapshot map(JsonNode body) {
        JsonNode method = body.path("method");
        JsonNode channel = body.path("channel");
        if (channel.isMissingNode() || channel.isNull()) {
            channel = body.path("selectedChannel");
        }
        return new PaymentSnapshot(
                text(body, "id"),
                text(body, "storeId"),
                text(body, "transactionId"),
                status(text(body, "status")),
                body.path("amount").path("total").asLong(-1),
                text(body, "currency"),
                text(method, "type"),
                text(method, "provider"),
                text(channel, "type"),
                instant(body, "paidAt"),
                cancellations(body.path("cancellations"))
        );
    }

    private List<PaymentSnapshot.CancellationSnapshot> cancellations(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<PaymentSnapshot.CancellationSnapshot> result = new ArrayList<>();
        nodes.forEach(node -> result.add(new PaymentSnapshot.CancellationSnapshot(
                text(node, "id"),
                text(node, "status"),
                node.path("totalAmount").asLong(0),
                instant(node, "requestedAt"),
                instant(node, "cancelledAt")
        )));
        return List.copyOf(result);
    }

    private PaymentSnapshot.Status status(String value) {
        if (value == null) {
            return PaymentSnapshot.Status.UNKNOWN;
        }
        try {
            return PaymentSnapshot.Status.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return PaymentSnapshot.Status.UNKNOWN;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

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
}
