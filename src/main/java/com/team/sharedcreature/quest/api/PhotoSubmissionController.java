package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.quest.application.PhotoVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사진 EXIF 퀘스트 제출 요청을 처리합니다.
 */
@RestController
@RequestMapping("/api/v1/photo-submissions")
@RequiredArgsConstructor
public class PhotoSubmissionController {

    /** 사진 제출과 EXIF 판정을 담당합니다. */
    private final PhotoVerificationService photoVerificationService;

    /**
     * 사진을 제출해 EXIF 판정 결과와 완료·보상 정보를 반환합니다.
     *
     * @param loginUser 인증된 사용자
     * @param assignmentId 퀘스트 배정 ID
     * @param file 제출 JPEG 사진
     * @return 제출 및 검증 결과를 담은 공통 응답
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PhotoSubmissionResponse> submitPhoto(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam("assignmentId") Long assignmentId,
            @RequestPart("file") MultipartFile file
    ) {
        // 배정 ID는 양수만 허용합니다.
        if (assignmentId == null || assignmentId < 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "퀘스트 배정 ID는 1 이상의 정수여야 합니다."
            );
        }

        // 파일 크기를 확인한 뒤 바이트 배열로 읽어 서비스에 전달합니다.
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "업로드된 파일이 비어 있습니다."
            );
        }

        try {
            PhotoSubmissionResponse result =
                    photoVerificationService.submit(
                            loginUser.id(),
                            assignmentId,
                            file.getContentType(),
                            file.getBytes()
                    );

            return ApiResponse.success(result);
        } catch (java.io.IOException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "업로드된 사진을 읽지 못했습니다."
            );
        }
    }
}