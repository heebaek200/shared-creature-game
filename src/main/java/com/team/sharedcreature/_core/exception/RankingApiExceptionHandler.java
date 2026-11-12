package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponse;
import com.team.sharedcreature._core.api.ErrorResponseFactory;
import com.team.sharedcreature.ranking.api.RankingController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 랭킹 API의 Query 오류 코드를 이슈 계약에 맞게 변환합니다.
 * 적용 대상을 RankingController로 제한합니다.
 */
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RankingController.class)
public class RankingApiExceptionHandler {

    private final ErrorResponseFactory responseFactory;

    /**
     * 랭킹 조회의 업무 예외를 공통 오류 응답으로 변환합니다.
     * 중복·미등록 Query의 요청 형식 오류만 필드 값 오류로 변경합니다.
     *
     * @param exception 발생한 업무 예외
     * @param request 현재 HTTP 요청
     * @return HTTP 상태와 공통 오류 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode code = exception.errorCode();

        // Context Path를 제외한 API 경로를 비교합니다.
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        boolean rankingQuery = "GET".equals(request.getMethod())
                && request.getQueryString() != null
                && ("/api/v1/rankings/members".equals(path)
                || "/api/v1/rankings/groups".equals(path));

        // 기존 공통 Interceptor가 발생시킨 Query 오류 코드를 변환합니다.
        if (rankingQuery && code == ErrorCode.INVALID_REQUEST) {
            code = ErrorCode.INVALID_FIELD_VALUE;
        }

        Object requestId = request.getAttribute("requestId");
        String traceId = requestId == null
                ? "unavailable"
                : requestId.toString();

        // 기존 Factory를 사용하여 오류 Envelope 형식을 유지합니다.
        return ResponseEntity.status(code.status()).body(
                responseFactory.create(
                        code,
                        exception.getMessage(),
                        List.of(),
                        traceId
                )
        );
    }
}