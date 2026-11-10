package com.team.sharedcreature.admin.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import com.team.sharedcreature.admin.infrastructure.ApiIdempotencyRecordRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/** 외부 효과 API의 사용자·Operation·Key별 최초 결과를 보존합니다. */
@Service
public class ApiIdempotencyService {

    private final ApiIdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 멱등 레코드 저장과 응답 직렬화 의존성을 주입받습니다. */
    public ApiIdempotencyService(
            ApiIdempotencyRecordRepository repository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 같은 요청의 완료 결과를 반환하고 신규 요청이면 처리권을 생성합니다. */
    public <T> Claim<T> claim(
            Long actorUserId,
            String operation,
            String idempotencyKey,
            String canonicalRequest,
            Class<T> responseType
    ) {
        String requestHash = sha256(canonicalRequest);
        Instant now = clock.instant();
        int inserted = repository.insertProcessingIfAbsent(
                actorUserId,
                operation,
                idempotencyKey,
                requestHash,
                now
        );
        ApiIdempotencyRecord record = repository
                .findByActorUserIdAndOperationAndIdempotencyKey(actorUserId, operation, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("멱등 처리권을 조회할 수 없습니다."));
        if (inserted == 0) {
            if (!record.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (!"COMPLETED".equals(record.getStatus())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
            }
            try {
                return Claim.replayed(record, objectMapper.readValue(record.getResponseSnapshot(), responseType));
            } catch (JacksonException exception) {
                throw new IllegalStateException("저장된 멱등 응답을 읽을 수 없습니다.", exception);
            }
        }
        return Claim.created(record);
    }

    /** 신규 처리의 최초 성공 응답을 저장합니다. */
    public void complete(Claim<?> claim, int httpStatus, String resourceType, Long resourceId, Object response) {
        if (claim.replayed()) {
            return;
        }
        try {
            claim.record().complete(
                    httpStatus,
                    resourceType,
                    resourceId,
                    objectMapper.writeValueAsString(response),
                    clock.instant()
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("멱등 응답을 저장할 수 없습니다.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /** 신규 처리권 또는 완료된 재사용 응답을 표현합니다. */
    public record Claim<T>(ApiIdempotencyRecord record, boolean replayed, T response) {
        static <T> Claim<T> created(ApiIdempotencyRecord record) {
            return new Claim<>(record, false, null);
        }

        static <T> Claim<T> replayed(ApiIdempotencyRecord record, T response) {
            return new Claim<>(record, true, response);
        }
    }
}
