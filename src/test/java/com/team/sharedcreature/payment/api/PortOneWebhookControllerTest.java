package com.team.sharedcreature.payment.api;

import com.team.sharedcreature.payment.application.PaymentWebhookService;
import com.team.sharedcreature.payment.application.PortOneWebhookVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PortOneWebhookControllerTest {

    @Test
    void 유효한_Webhook은_202와_빈_data를_반환한다() {
        PaymentWebhookService service = mock(PaymentWebhookService.class);
        PortOneWebhookController controller = new PortOneWebhookController(service);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        var response = controller.receivePortOneWebhook("event-1", "100", "signature", body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEmpty();
        verify(service).receive("{}", "event-1", "signature", "100");
    }

    @Test
    void 서명_오류는_공통_Envelope없이_400_빈_Body를_반환한다() {
        PaymentWebhookService service = mock(PaymentWebhookService.class);
        PortOneWebhookVerifier.InvalidWebhookSignatureException exception =
                mock(PortOneWebhookVerifier.InvalidWebhookSignatureException.class);
        doThrow(exception).when(service).receive(anyString(), anyString(), anyString(), anyString());
        PortOneWebhookController controller = new PortOneWebhookController(service);

        var response = controller.receivePortOneWebhook(
                "event-1",
                "100",
                "bad-signature",
                "{}".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNull();
    }
}
