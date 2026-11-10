package com.team.sharedcreature.payment.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature.payment.application.PaymentWebhookService;
import com.team.sharedcreature.payment.application.PortOneWebhookVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** PortOne 원본 Webhook 요청을 서명 Header와 함께 수신합니다. */
@RestController
@RequestMapping("/api/v1/payments/webhooks/portone")
public class PortOneWebhookController {

    private final PaymentWebhookService webhookService;

    public PortOneWebhookController(PaymentWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /** 서명 검증된 이벤트를 비동기 처리 대상으로 수락합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> receivePortOneWebhook(
            @RequestHeader("webhook-id") String eventId,
            @RequestHeader("webhook-timestamp") String timestamp,
            @RequestHeader("webhook-signature") String signature,
            @RequestBody byte[] rawBody
    ) {
        try {
            webhookService.receive(
                    new String(rawBody, StandardCharsets.UTF_8),
                    eventId,
                    signature,
                    timestamp
            );
            return ResponseEntity.accepted().body(ApiResponse.success(Map.of()));
        } catch (PortOneWebhookVerifier.InvalidWebhookSignatureException exception) {
            return ResponseEntity.badRequest().build();
        }
    }
}
