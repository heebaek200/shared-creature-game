package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import com.team.sharedcreature.admin.infrastructure.ApiIdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiIdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");

    @Mock private ApiIdempotencyRecordRepository repository;

    private ApiIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new ApiIdempotencyService(
                repository,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 원자적으로_신규_처리권을_생성한다() {
        ApiIdempotencyRecord record = mock(ApiIdempotencyRecord.class);
        when(repository.insertProcessingIfAbsent(
                eq(7L), eq("CREATE_PURCHASE"), eq("key-1"), any(), eq(NOW)
        )).thenReturn(1);
        when(repository.findByActorUserIdAndOperationAndIdempotencyKey(7L, "CREATE_PURCHASE", "key-1"))
                .thenReturn(Optional.of(record));

        var claim = service.claim(7L, "CREATE_PURCHASE", "key-1", "shopItemId=101", Replay.class);

        assertThat(claim.replayed()).isFalse();
        assertThat(claim.record()).isSameAs(record);
    }

    @Test
    void 같은_요청의_완료된_응답을_재사용한다() {
        ApiIdempotencyRecord record = mock(ApiIdempotencyRecord.class);
        when(repository.insertProcessingIfAbsent(
                eq(7L), eq("CREATE_PURCHASE"), eq("key-1"), any(), eq(NOW)
        )).thenReturn(0);
        when(repository.findByActorUserIdAndOperationAndIdempotencyKey(7L, "CREATE_PURCHASE", "key-1"))
                .thenReturn(Optional.of(record));
        when(record.getRequestHash()).thenReturn(sha256("shopItemId=101"));
        when(record.getStatus()).thenReturn("COMPLETED");
        when(record.getResponseSnapshot()).thenReturn("{\"purchaseId\":21}");

        var claim = service.claim(7L, "CREATE_PURCHASE", "key-1", "shopItemId=101", Replay.class);

        assertThat(claim.replayed()).isTrue();
        assertThat(claim.response().purchaseId()).isEqualTo(21L);
    }

    @Test
    void 같은_키의_다른_요청은_거절한다() {
        ApiIdempotencyRecord record = mock(ApiIdempotencyRecord.class);
        when(repository.insertProcessingIfAbsent(
                eq(7L), eq("CREATE_PURCHASE"), eq("key-1"), any(), eq(NOW)
        )).thenReturn(0);
        when(repository.findByActorUserIdAndOperationAndIdempotencyKey(7L, "CREATE_PURCHASE", "key-1"))
                .thenReturn(Optional.of(record));
        when(record.getRequestHash()).thenReturn(sha256("shopItemId=999"));

        assertThatThrownBy(() -> service.claim(
                7L, "CREATE_PURCHASE", "key-1", "shopItemId=101", Replay.class
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Replay(Long purchaseId) {
    }
}
