package com.team.sharedcreature.quest.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 사진 EXIF 제출 및 판정 결과 응답입니다.
 *
 * nullable 필드도 API 계약에 따라 JSON에 null로 포함합니다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PhotoSubmissionResponse(
        /** 사진 제출 이력 ID */
        Long photoSubmissionId,

        /** 퀘스트 배정 ID */
        Long assignmentId,

        /** 사용자·배정별 제출 시도 번호 */
        int attemptNumber,

        /** 판정 상태: PENDING, VALID, INVALID */
        String verificationStatus,

        /** 실패 사유. 성공했거나 아직 판정 중이면 null */
        String failureReason,

        /** 서버가 제출을 접수한 시각 */
        Instant uploadedAt,

        /** EXIF 촬영 시각. 추출할 수 없으면 null */
        Instant capturedAt,

        /** 보상 확정 시 생성된 완료 ID. 보상이 없으면 null */
        Long completionId
) {
}