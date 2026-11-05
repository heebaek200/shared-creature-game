package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.FieldErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Controller 예외를 공통 JSON 오류 계약으로 변환합니다.
 */
@RestControllerAdvice
public class RestApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestApiExceptionHandler.class);
    private final Clock clock;

    /**
     * 예외 응답 시각을 제공하는 Clock을 주입받습니다.
     *
     * @param clock 업무 공통 Clock
     */
    public RestApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** 업무 예외를 설계된 상태와 코드로 응답합니다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), List.of(), request);
    }

    /** Bean Validation 실패를 필드 오류 배열로 응답합니다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return response(HttpStatus.BAD_REQUEST, "FIELD_VALIDATION_FAILED", "입력값을 확인해 주세요.", fields, request);
    }

    /** 비어 있거나 해석할 수 없는 JSON 본문을 내부 오류가 아닌 입력 오류로 변환합니다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 확인해 주세요.", List.of(), request);
    }

    /** Path·Query 값의 타입 변환 실패를 입력 필드 오류로 변환합니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_FIELD_VALUE",
                "요청 필드의 타입이나 형식을 확인해 주세요.",
                List.of(),
                request
        );
    }

    /** 존재하지 않는 API 경로를 안전한 404 응답으로 변환합니다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다.", List.of(), request);
    }

    /** 예상하지 못한 오류는 세부 내용을 숨기고 추적 ID만 반환합니다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected API failure. requestId={}", traceId(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "요청을 처리하지 못했습니다.", List.of(), request);
    }

    private FieldErrorResponse toFieldError(FieldError error) {
        return new FieldErrorResponse(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorResponse> fields,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                code,
                message,
                fields,
                traceId(request),
                Instant.now(clock)
        ));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? "unavailable" : value.toString();
    }
}
