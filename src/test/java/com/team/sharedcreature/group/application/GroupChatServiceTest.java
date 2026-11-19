package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.realtime.GroupChatEventPublisher;
import com.team.sharedcreature.group.api.GroupChatMessageResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void Unicode_공백을_제거하고_코드포인트_80자까지_저장한다() {
        GroupChatTransactionService transactionService = mock(GroupChatTransactionService.class);
        GroupChatEventPublisher publisher = mock(GroupChatEventPublisher.class);
        GroupChatService service = new GroupChatService(
                new GroupChatRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
                transactionService,
                publisher);
        String content = "😀".repeat(80);
        GroupChatMessageResponse response = response(content);
        when(transactionService.create(1L, content)).thenReturn(response);

        assertThat(service.create(1L, "\u00a0" + content + "\u3000")).isEqualTo(response);
        verify(publisher).publish(response);
    }

    @Test
    void 줄바꿈과_81개_코드포인트는_거부한다() {
        GroupChatService service = new GroupChatService(
                new GroupChatRateLimiter(Clock.systemUTC()),
                mock(GroupChatTransactionService.class),
                mock(GroupChatEventPublisher.class));

        assertThatThrownBy(() -> service.create(1L, "첫 줄\n둘째 줄"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(1L, "😀".repeat(81)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void Commit_뒤_STOMP_발행_실패는_REST_성공을_바꾸지_않는다() {
        GroupChatTransactionService transactionService = mock(GroupChatTransactionService.class);
        GroupChatEventPublisher publisher = mock(GroupChatEventPublisher.class);
        GroupChatService service = new GroupChatService(
                new GroupChatRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
                transactionService,
                publisher);
        GroupChatMessageResponse response = response("안녕");
        when(transactionService.create(1L, "안녕")).thenReturn(response);
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(response);

        assertThat(service.create(1L, "안녕")).isEqualTo(response);
    }

    private GroupChatMessageResponse response(String content) {
        return new GroupChatMessageResponse(
                "550e8400-e29b-41d4-a716-446655440000",
                10L,
                1L,
                "새싹",
                content,
                NOW);
    }
}
