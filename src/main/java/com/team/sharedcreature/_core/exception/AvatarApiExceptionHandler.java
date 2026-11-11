package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature.avatar.api.AvatarController;
import com.team.sharedcreature.avatar.api.ShopController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 이슈 20의 상점·아바타 API에 한정해 오류 응답을 보완합니다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        ShopController.class,
        AvatarController.class
})

public class AvatarApiExceptionHandler {

    private final ErrorResponseFactory responseFactory;

    /**
     * 기존 공통 오류 응답 생성기를 주입받습니다.
     *
     * @param responseFactory 공통 오류 응답 생성기
     */
    public AvatarApiExceptionHandler(ErrorResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    /**
     * JSON 내부에 감싸진 장착 요청 타입 오류를 구분합니다.
     *
     * @param exception JSON 본문 변환 예외
     * @param request 현재 HTTP 요청
     * @return 필드 값 오류 또는 요청 형식 오류 응답
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Throwable cause = exception.getCause();

        while (cause != null) {
            // 장착 DTO에서 발생시킨 타입 오류만 업무 오류로 복원합니다.
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

        // JSON 문법 오류·미등록 필드·금지된 null은 기존 형식을 유지합니다.
        return response(
                ErrorCode.INVALID_REQUEST,
                "요청 본문을 확인해 주세요.",
                request
        );
    }

    /**
     * 목록 Query 오류 코드를 이슈 20의 계약에 맞게 변환합니다.
     *
     * @param exception 업무 예외
     * @param request 현재 HTTP 요청
     * @return 요청에 맞는 업무 오류 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = exception.errorCode();

        // 배포 시 Context Path가 있어도 API 경로만 비교합니다.
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        boolean listQuery = "GET".equals(request.getMethod())
                && request.getQueryString() != null
                && ("/api/v1/shop-items".equals(path)
                || "/api/v1/owned-items".equals(path));

        // 공통 인터셉터의 오류를 두 목록 API에서만 변환합니다.
        if (listQuery && code == ErrorCode.INVALID_REQUEST) {
            code = ErrorCode.INVALID_FIELD_VALUE;
        }

        return response(code, exception.getMessage(), request);
    }

    /**
     * 기존 공통 오류 Envelope을 생성합니다.
     *
     * @param code 응답할 오류 코드
     * @param message 사용자 안내 메시지
     * @param request 추적 ID를 확인할 요청
     * @return HTTP 상태와 공통 오류 본문
     */
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
