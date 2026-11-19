package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.api.FieldErrorResponse;
import com.team.sharedcreature.avatar.api.AvatarController;
import com.team.sharedcreature.avatar.api.ShopController;
import com.team.sharedcreature.payment.api.AdminRefundController;
import com.team.sharedcreature.payment.api.RefundController;
import com.team.sharedcreature.group.application.ChatRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Controller 예외를 공통 JSON 오류 계약으로 변환합니다.
 */
@Slf4j
@RestControllerAdvice
public class RestApiExceptionHandler {

    private static final List<Class<?>> FIELD_VALUE_BODY_CONTROLLERS = List.of(
            AvatarController.class, ShopController.class, RefundController.class, AdminRefundController.class);
    private final ErrorResponseFactory responseFactory;

    /**
     * 공통 오류 응답 생성기를 주입받습니다.
     *
     * @param responseFactory 오류 Envelope 생성기
     */
    public RestApiExceptionHandler(ErrorResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    /** 업무 예외를 설계된 상태와 코드로 응답합니다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception, HttpServletRequest request) {
        ErrorCode code = exception.errorCode();
        if (code == ErrorCode.INVALID_REQUEST && usesFieldValueForListQuery(request)) {
            code = ErrorCode.INVALID_FIELD_VALUE;
        }
        return response(code, exception.getMessage(), List.of(), request);
    }

    /** 채팅 전송 제한 응답에 지연 초 Header를 추가합니다. */
    @ExceptionHandler(ChatRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleChatRateLimit(
            ChatRateLimitException exception,
            HttpServletRequest request
    ) {
        ErrorResponse body = responseFactory.create(
                ErrorCode.RATE_LIMIT_EXCEEDED,
                exception.getMessage(),
                List.of(),
                ErrorResponseFactory.requestTraceId(request)
        );
        return ResponseEntity.status(ErrorCode.RATE_LIMIT_EXCEEDED.status())
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(body);
    }

    /** 목록 API의 Query 오류 코드는 Context Path를 제외한 기존 API 경로로 구분합니다. */
    private boolean usesFieldValueForListQuery(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) || request.getQueryString() == null) {
            return false;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (path) {
            case "/api/v1/shop-items", "/api/v1/owned-items",
                    "/api/v1/rankings/members", "/api/v1/rankings/groups",
                    "/api/v1/refund-requests", "/api/v1/admin/refund-requests" -> true;
            default -> false;
        };
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
        return response(ErrorCode.INVALID_FIELD_VALUE, null, fields, request);
    }

    /** Form 및 Query Binding 실패를 필드 오류 배열로 응답합니다. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBinding(BindException exception, HttpServletRequest request) {
        List<FieldErrorResponse> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return response(ErrorCode.INVALID_FIELD_VALUE, null, fields, request);
    }

    /** Query·Path 타입 오류를 해당 입력 필드의 오류로 변환합니다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        FieldErrorResponse fieldError = new FieldErrorResponse(exception.getName(), "올바른 형식의 값을 입력해 주세요.");
        return response(ErrorCode.INVALID_FIELD_VALUE, null, List.of(fieldError), request);
    }

    /** Controller 메서드 파라미터의 제약조건 오류를 필드 오류 배열로 변환합니다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        if (exception.isForReturnValue()) {
            return handleUnexpected(exception, request);
        }
        List<FieldErrorResponse> fields = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorResponse(
                                parameterName(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage()
                        )))
                .toList();
        return response(ErrorCode.INVALID_FIELD_VALUE, null, fields, request);
    }

    /** Method Validation의 제약조건 오류를 필드 오류 배열로 변환합니다. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fields = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorResponse(
                        lastPathSegment(violation.getPropertyPath().toString()),
                        violation.getMessage()
                ))
                .toList();
        return response(ErrorCode.INVALID_FIELD_VALUE, null, fields, request);
    }

    /** 비어 있거나 해석할 수 없는 JSON 본문을 내부 오류가 아닌 입력 오류로 변환합니다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        // 기존 계약에 따라 아바타·환불 API의 DTO에서 거절한 필드 값만 복원합니다.
        if (handler instanceof HandlerMethod method
                && FIELD_VALUE_BODY_CONTROLLERS.stream().anyMatch(type -> type.isAssignableFrom(method.getBeanType()))) {
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BusinessException business
                        && business.errorCode() == ErrorCode.INVALID_FIELD_VALUE) {
                    return response(ErrorCode.INVALID_FIELD_VALUE, business.getMessage(), List.of(), request);
                }
            }
        }
        return response(ErrorCode.INVALID_REQUEST, "요청 본문을 확인해 주세요.", List.of(), request);
    }

    /** 필수 입력 누락과 지원하지 않는 Content-Type을 요청 형식 오류로 변환합니다. */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            HttpMediaTypeNotSupportedException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return response(ErrorCode.INVALID_REQUEST, null, List.of(), request);
    }

    /** Multipart 제한을 초과한 요청을 413 오류로 변환합니다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleFileTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return response(ErrorCode.FILE_TOO_LARGE, null, List.of(), request);
    }

    /** 존재하지 않는 API 경로를 안전한 404 응답으로 변환합니다. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception exception, HttpServletRequest request) {
        return response(ErrorCode.RESOURCE_NOT_FOUND, null, List.of(), request);
    }

    /** 예상하지 못한 오류는 세부 내용을 숨기고 추적 ID만 반환합니다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        return internalError(exception, null, request);
    }

    /** 내부 원인은 로그에 기록하고 사전에 정한 사용자 안내 문구를 반환합니다. */
    @ExceptionHandler(InternalApiException.class)
    public ResponseEntity<ErrorResponse> handleInternal(InternalApiException exception, HttpServletRequest request) {
        return internalError(exception, exception.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> internalError(
            Exception exception, String clientMessage, HttpServletRequest request
    ) {
        log.error("Unexpected API failure. requestId={}", ErrorResponseFactory.requestTraceId(request), exception);
        return response(ErrorCode.INTERNAL_SERVER_ERROR, clientMessage, List.of(), request);
    }

    private FieldErrorResponse toFieldError(FieldError error) {
        return new FieldErrorResponse(error.getField(), error.getDefaultMessage());
    }

    private String parameterName(String name) {
        return name == null || name.isBlank() ? "request" : name;
    }

    private String lastPathSegment(String path) {
        int separator = path.lastIndexOf('.');
        return parameterName(separator < 0 ? path : path.substring(separator + 1));
    }

    private ResponseEntity<ErrorResponse> response(
            ErrorCode errorCode,
            String message,
            List<FieldErrorResponse> fields,
            HttpServletRequest request
    ) {
        return responseFactory.createMvcResponse(errorCode, message, fields, request);
    }
}
