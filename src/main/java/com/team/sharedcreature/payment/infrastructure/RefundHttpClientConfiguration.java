package com.team.sharedcreature.payment.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 환불 PG 호출에만 사용하는 HTTP 클라이언트를 구성합니다.
 * 기존 결제 조회 클라이언트의 설정은 변경하지 않습니다.
 */
@Configuration(proxyBeanMethods = false)
public class RefundHttpClientConfiguration {

    /**
     * 연결·응답 대기 시간을 제한한 환불 전용 클라이언트를 생성합니다.
     *
     * @param properties 기존 PortOne 접속 설정
     * @param builder Spring에서 제공하는 RestClient Builder
     * @return 환불 전용 RestClient
     */
    @Bean("refundRestClient")
    public RestClient refundRestClient(
            PortOneProperties properties,
            RestClient.Builder builder
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                // 인증 정보를 포함한 요청이 다른 주소로 자동 이동하지 않습니다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        // PG 처리 지연을 고려하여 PortOne의 최소 60초 읽기 타임아웃 권고를 반영합니다.
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        // 주입된 Builder를 복제하여 다른 클라이언트 설정에 영향을 주지 않습니다.
        return builder.clone()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "PortOne " + properties.apiSecret()
                )
                .build();
    }
}