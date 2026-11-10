package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.quest.application.PhotoVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PhotoSubmissionController의 입력 확인과 서비스 전달을 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class PhotoSubmissionControllerTest {

    /** 10 MiB를 초과하는 파일의 경계값 테스트에 사용합니다. */
    private static final int TEN_MIB = 10 * 1024 * 1024;

    @Mock
    private PhotoVerificationService photoVerificationService;

    @Mock
    private HttpServletRequest request;

    private PhotoSubmissionController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new PhotoSubmissionController(photoVerificationService);
        when(request.getQueryString()).thenReturn(null);
        when(request.getParts()).thenReturn(List.of());
    }

    @Test
    void 정상_사진_요청을_로그인사용자와_함께_서비스에_전달한다() throws Exception {
        LoginUser loginUser = new LoginUser(
                7L,
                "테스트 사용자",
                Set.of("MEMBER")
        );
        byte[] jpegBytes = {
                (byte) 0xFF,
                (byte) 0xD8,
                (byte) 0xFF,
                0x01
        };
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.jpg",
                "image/jpeg",
                jpegBytes
        );
        Instant uploadedAt = Instant.parse("2026-11-10T01:00:00Z");
        PhotoSubmissionResponse expected = new PhotoSubmissionResponse(
                21L,
                11L,
                1,
                "VALID",
                null,
                uploadedAt,
                uploadedAt,
                31L
        );

        when(photoVerificationService.submit(
                eq(7L),
                eq(11L),
                eq("image/jpeg"),
                any(byte[].class)
        )).thenReturn(expected);

        ApiResponse<PhotoSubmissionResponse> response =
                controller.submitPhoto(loginUser, 11L, file, request);

        assertThat(response.data()).isSameAs(expected);

        ArgumentCaptor<byte[]> bytesCaptor =
                ArgumentCaptor.forClass(byte[].class);

        verify(photoVerificationService).submit(
                eq(7L),
                eq(11L),
                eq("image/jpeg"),
                bytesCaptor.capture()
        );
        assertThat(bytesCaptor.getValue()).containsExactly(jpegBytes);
    }

    @Test
    void 배정_ID가_양수가_아니면_INVALID_FIELD_VALUE로_거절한다() {
        LoginUser loginUser = new LoginUser(
                7L,
                "테스트 사용자",
                Set.of("MEMBER")
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> controller.submitPhoto(loginUser, 0L, file, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));

        verifyNoInteractions(photoVerificationService);
    }

    @Test
    void 파일이_10_MiB를_초과하면_FILE_TOO_LARGE로_거절한다() {
        LoginUser loginUser = new LoginUser(
                7L,
                "테스트 사용자",
                Set.of("MEMBER")
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.jpg",
                "image/jpeg",
                new byte[TEN_MIB + 1]
        );

        assertThatThrownBy(() -> controller.submitPhoto(loginUser, 11L, file, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.FILE_TOO_LARGE));

        verifyNoInteractions(photoVerificationService);
    }
}
