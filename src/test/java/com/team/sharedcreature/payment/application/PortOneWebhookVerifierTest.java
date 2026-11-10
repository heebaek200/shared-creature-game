package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.infrastructure.PortOneProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneWebhookVerifierTest {

    private static final byte[] SECRET = "portone-webhook-test-secret".getBytes(StandardCharsets.UTF_8);
    private static final String BODY = """
            {"type":"Transaction.Paid","timestamp":"2026-11-09T08:00:00Z","data":{
              "storeId":"store-test","paymentId":"payment-21","transactionId":"tx-1"
            }}
            """;

    @Test
    void 공식_Verifier가_유효한_원본_Body와_Standard_Header를_검증한다() {
        PortOneWebhookVerifier verifier = verifier();
        String eventId = "event-1";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signature(eventId, timestamp, BODY);

        assertThatCode(() -> verifier.verify(BODY, eventId, signature, timestamp))
                .doesNotThrowAnyException();
    }

    @Test
    void 원본_Body가_변조되면_서명_검증을_거절한다() {
        PortOneWebhookVerifier verifier = verifier();
        String eventId = "event-1";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = signature(eventId, timestamp, BODY);

        assertThatThrownBy(() -> verifier.verify(BODY + " ", eventId, signature, timestamp))
                .isInstanceOf(PortOneWebhookVerifier.InvalidWebhookSignatureException.class);
    }

    private PortOneWebhookVerifier verifier() {
        return new PortOneWebhookVerifier(new PortOneProperties(
                "store-test",
                "channel-test",
                "api-secret",
                "whsec_" + Base64.getEncoder().encodeToString(SECRET),
                "https://api.portone.io"
        ));
    }

    private String signature(String eventId, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (eventId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)
            );
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
