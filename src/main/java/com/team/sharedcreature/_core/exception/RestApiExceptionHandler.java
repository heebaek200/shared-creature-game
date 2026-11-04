package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature._core.api.FieldErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Controller 예외를 공통 JSON 오류 계약으로 변환합니다.
 */
@RestControllerAdvice
public class RestApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestApiExceptionHandler.class);
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
        return response(exception.errorCode(), exception.getMessage(), List.of(), request);
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
        var fieldError = new FieldErrorResponse(exception.getName(), "올바른 형식의 값을 입력해 주세요.");
        return response(ErrorCode.INVALID_FIELD_VALUE, null, List.of(fieldError), request);
    }

    /** Controller 메서드 파라미터의 제약조건 오류를 필드 오류 배열로 변환합니다. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
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
        return response(ErrorCode.INVALID_REQUEST, "요청 본문을 확인해 주세요.", List.of(), request);
    }

    /** 필수 입력 누락과 지원하지 않는 Content-Type을 요청 형식 오류로 변환합니다. */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            HttpMediaTypeNotSupportedException.class
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
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(ErrorCode.RESOURCE_NOT_FOUND, null, List.of(), request);
    }

    /** 예상하지 못한 오류는 세부 내용을 숨기고 추적 ID만 반환합니다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected API failure. requestId={}", traceId(request), exception);
        return response(ErrorCode.INTERNAL_SERVER_ERROR, null, List.of(), request);
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
        return ResponseEntity.status(errorCode.status()).body(responseFactory.create(
                errorCode,
                message,
                fields,
                traceId(request)
        ));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        return value == null ? "unavailable" : value.toString();
    }
}
