package com.team.sharedcreature._core.exception;

import com.team.sharedcreature._core.api.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestApiExceptionHandlerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    private final RestApiExceptionHandler handler = new RestApiExceptionHandler(new ErrorResponseFactory(clock));

    @Test
    void 비어_있거나_해석할_수_없는_JSON은_400_INVALID_REQUEST로_응답한다() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("requestId")).thenReturn("request-1");

        var response = handler.handleUnreadableBody(
                mock(HttpMessageNotReadableException.class), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().timestamp()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
    }

    @Test
    void 업무_예외는_오류_코드에_정의된_HTTP_상태로_응답한다() {
        HttpServletRequest request = request("request-2");

        var response = handler.handleBusiness(
                new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    @Test
    void Multipart_제한_초과는_413_FILE_TOO_LARGE로_응답한다() {
        var response = handler.handleFileTooLarge(
                new MaxUploadSizeExceededException(10L * 1024 * 1024), request("request-3"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void 예상하지_못한_예외는_내부_메시지를_숨긴다() {
        var response = handler.handleUnexpected(
                new IllegalStateException("database-password"), request("request-4"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).doesNotContain("database-password");
    }

    private HttpServletRequest request(String requestId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("requestId")).thenReturn(requestId);
        return request;
    }
}
