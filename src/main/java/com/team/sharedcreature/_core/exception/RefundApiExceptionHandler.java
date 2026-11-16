package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature.payment.api.AdminRefundController;
import com.team.sharedcreature.payment.api.RefundController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 이슈 22의 사용자·관리자 환불 API 오류를 처리합니다.
 * 적용 대상을 두 Controller로 제한합니다.
 */
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        RefundController.class,
        AdminRefundController.class
})
public class RefundApiExceptionHandler {

    private final ErrorResponseFactory responseFactory;

    /**
     * JSON 변환 중 발생한 사유 필드의 타입 오류를 복원합니다.
     * JSON 문법 오류와 미등록 필드 등은 INVALID_REQUEST로 처리합니다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Throwable cause = exception.getCause();

        while (cause != null) {
            // DTO에서 거절한 숫자·boolean 등의 사유 입력입니다.
            if (cause instanceof BusinessException business
                    && business.errorCode() == ErrorCode.INVALID_FIELD_VALUE) {
                return response(
                        ErrorCode.INVALID_FIELD_VALUE,
                        business.getMessage(),
                        request
                );
            }

            cause = cause.getCause();
        }

        return response(
                ErrorCode.INVALID_REQUEST,
                "요청 본문을 확인해 주세요.",
                request
        );
    }

    /**
     * 업무 오류를 반환하고 목록 Query의 오류 코드를 계약에 맞춥니다.
     * 목록 외 요청에는 오류 코드 변환을 적용하지 않습니다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = exception.errorCode();

        // 서버의 Context Path를 제외하고 API 경로를 비교합니다.
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        boolean listQuery = "GET".equals(request.getMethod())
                && request.getQueryString() != null
                && ("/api/v1/refund-requests".equals(path)
                || "/api/v1/admin/refund-requests".equals(path));

        // 공통 Interceptor의 중복·미등록 Query 오류만 변환합니다.
        if (listQuery && code == ErrorCode.INVALID_REQUEST) {
            code = ErrorCode.INVALID_FIELD_VALUE;
        }

        return response(code, exception.getMessage(), request);
    }

    /** 기존 오류 Envelope과 요청 추적 ID를 사용합니다. */
    private ResponseEntity<ErrorResponse> response(
            ErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        Object requestId = request.getAttribute("requestId");
        String traceId = requestId == null
                ? "unavailable"
                : requestId.toString();

        return ResponseEntity.status(code.status()).body(
                responseFactory.create(
                        code,
                        message,
                        List.of(),
                        traceId
                )
        );
    }
}
