package com.team.sharedcreature._core.api;

import com.team.sharedcreature._core.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * MVC와 Security 처리 경로에서 동일한 오류 Envelope를 생성합니다.
 */
@Component
public class ErrorResponseFactory {

    private final Clock clock;

    /** 오류 응답 시각을 제공하는 공통 Clock을 주입받습니다. */
    public ErrorResponseFactory(Clock clock) {
        this.clock = clock;
    }

    /** 지정한 오류 코드와 요청 문맥으로 안전한 오류 응답을 생성합니다. */
    public ErrorResponse create(
            ErrorCode errorCode,
            String message,
            List<FieldErrorResponse> fieldErrors,
            String traceId
    ) {
        return new ErrorResponse(
                errorCode.code(),
                message == null || message.isBlank() ? errorCode.defaultMessage() : message,
                fieldErrors,
                traceId,
                Instant.now(clock)
        );
    }
}
