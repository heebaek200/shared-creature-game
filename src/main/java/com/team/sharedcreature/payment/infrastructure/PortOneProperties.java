package com.team.sharedcreature.payment.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** PortOne 공개 설정과 서버 비밀 설정을 환경변수에서 읽습니다. */
@ConfigurationProperties("portone")
public record PortOneProperties(
        String storeId,
        String channelKey,
        String apiSecret,
        String webhookSecret,
        String apiBaseUrl
) {
}
