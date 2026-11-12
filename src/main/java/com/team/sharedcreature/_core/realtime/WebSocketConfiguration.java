package com.team.sharedcreature._core.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.ArrayList;
import java.util.List;

/** `/ws` Native STOMP Endpoint와 그룹 토픽 Simple Broker를 구성합니다. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration
        implements WebSocketMessageBrokerConfigurer {

    private final GroupStompAuthorizationInterceptor authorizationInterceptor;
    private final WebSocketSessionHandshakeInterceptor handshakeInterceptor;
    private final ActiveWebSocketConnectionRegistry connectionRegistry;
    private final GroupStompErrorHandler errorHandler;
    private final String ngrokDomainUri;

    /** STOMP 인바운드 검증기와 허용 Origin 설정을 주입받습니다. */
    public WebSocketConfiguration(
            GroupStompAuthorizationInterceptor authorizationInterceptor,
            WebSocketSessionHandshakeInterceptor handshakeInterceptor,
            ActiveWebSocketConnectionRegistry connectionRegistry,
            GroupStompErrorHandler errorHandler,
            @Value("${ngrok.domain-uri:}") String ngrokDomainUri
    ) {
        this.authorizationInterceptor = authorizationInterceptor;
        this.handshakeInterceptor = handshakeInterceptor;
        this.connectionRegistry = connectionRegistry;
        this.errorHandler = errorHandler;
        this.ngrokDomainUri = ngrokDomainUri;
    }

    /** 애플리케이션 송신 없이 서버 그룹 토픽만 사용하는 Simple Broker를 활성화합니다. */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    /** SockJS 없이 `/ws` Native WebSocket Endpoint를 등록합니다. */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errorHandler);
        List<String> origins = new ArrayList<>(List.of("http://localhost:5173"));
        if (ngrokDomainUri != null && !ngrokDomainUri.isBlank()) {
            origins.add(ngrokDomainUri.trim());
        }
        registry.addEndpoint("/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(origins.toArray(String[]::new));
    }

    /** 모든 클라이언트 STOMP 프레임에 인증·권한·허용 명령 검사를 적용합니다. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorizationInterceptor);
    }

    /** 활성 연결을 추적해 탈퇴·강퇴 시 해당 WebSocket을 즉시 닫습니다. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(connectionRegistry);
    }
}
