package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.api.PhotoSubmissionResponse;
import com.team.sharedcreature.quest.api.QuestCompletionResponse;
import com.team.sharedcreature.quest.api.QuestCompletionService;
import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 사진 제출, EXIF 판정, 퀘스트 완료 요청을 연결합니다.
 */
@Service
@RequiredArgsConstructor
public class PhotoVerificationService {

    /** 업로드 사진의 최대 크기: 10 MiB */
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    /** 사용자별 분당 제출 허용 횟수 */
    private static final int MAX_REQUESTS_PER_MINUTE = 5;

    /** Offset이 없는 EXIF 촬영 시각에 적용할 시간대 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** EXIF DateTimeOriginal 형식 */
    private static final DateTimeFormatter EXIF_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private final PhotoSubmissionRepository photoSubmissionRepository;
    private final QuestAssignmentRepository questAssignmentRepository;
    private final UserRepository userRepository;
    private final GroupMembershipRepository membershipRepository;
    private final PhotoFileStorage photoFileStorage;
    private final PhotoProcessingLimiter photoProcessingLimiter;
    private final JpegExifReader jpegExifReader;
    private final QuestCompletionService questCompletionService;
    private final SeasonQueryService seasonQueryService;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /**
     * 사진을 접수하고 EXIF 판정 및 필요한 경우 퀘스트 보상을 처리합니다.
     *
     * @param userId 로그인 사용자 ID
     * @param assignmentId 퀘스트 배정 ID
     * @param contentType 업로드된 파일의 Content-Type
     * @param jpegBytes 업로드된 사진 바이트
     * @return 제출·판정 결과
     */
    public PhotoSubmissionResponse submit(
            Long userId,
            Long assignmentId,
            String contentType,
            byte[] jpegBytes
    ) {
        // 1. MIME과 파일 크기를 확인합니다. 이 거절은 제출 횟수에 포함되지 않습니다.
        validateUpload(contentType, jpegBytes);

        // 2. Signature와 EXIF APP1 크기도 이력 저장 전에 사전 검사합니다.
        jpegExifReader.validateJpegAndExifSize(jpegBytes);

        // 3. Worker 자리를 먼저 확보해 포화 요청이 제출 횟수에 포함되지 않게 합니다.
        try (PhotoProcessingLimiter.Permit permit =
                     photoProcessingLimiter.reserve()) {
            Instant uploadedAt = Instant.now(clock);
            String storageKey = UUID.randomUUID() + ".jpg";

            // 4. 사진 파일을 저장합니다. 제출 제한 검사에서 거절되면 파일도 제거합니다.
            try {
                storageKey = photoFileStorage.store(jpegBytes);
            } catch (IOException exception) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "사진 파일을 저장하지 못했습니다."
                );
            }

            PendingPhoto pending;
            try {
                // 5. 사용자 잠금 안에서 횟수를 확인하고 PENDING 이력을 Commit합니다.
                pending = createPendingSubmission(
                        userId,
                        assignmentId,
                        storageKey,
                        uploadedAt
                );
            } catch (RuntimeException exception) {
                // DB 저장이 거절되었으면 연결되지 않은 사진 파일을 정리합니다.
                try {
                    photoFileStorage.delete(storageKey);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
                throw exception;
            }

            JpegExifReader.ExifData exifData;
            try {
                // 6. PENDING 저장 후 예약한 Worker에서 EXIF만 파싱합니다.
                exifData = photoProcessingLimiter.execute(
                        permit,
                        () -> jpegExifReader.readExif(jpegBytes)
                );
            } catch (TimeoutException exception) {
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "PHOTO_PROCESSING_TIMEOUT",
                        null,
                        null,
                        null
                );
            } catch (PhotoProcessingLimiter.ExifTaskExecutionException exception) {
                // EXIF 파서가 데이터를 읽지 못하면 필수 촬영 시각을 추출하지 못한 실패로 기록합니다.
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "EXIF_CAPTURED_AT_MISSING",
                        null,
                        null,
                        null
                );
            }

            // 7. GPS 누락과 좌표 범위를 검사합니다.
            BigDecimal latitude = exifData.latitude(); // 정밀한 연산이 필요함 BigDecimal 사용
            BigDecimal longitude = exifData.longitude(); // 정밀한 연산이 필요함 BigDecimal 사용

            if (latitude == null || longitude == null) {
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "EXIF_GPS_MISSING",
                        null,
                        latitude,
                        longitude
                );
            }

            if (isCoordinateOutOfRange(latitude, longitude)) {
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "EXIF_GPS_OUT_OF_RANGE",
                        null,
                        latitude,
                        longitude
                );
            }

            // 8. EXIF 촬영 시각을 Instant로 바꿉니다.
            Instant capturedAt = parseCapturedAt(
                    exifData.capturedAtRaw(),
                    exifData.offsetTimeOriginalRaw()
            );

            if (capturedAt == null) {
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "EXIF_CAPTURED_AT_MISSING",
                        null,
                        latitude,
                        longitude
                );
            }

            // 9. 촬영 시각도 제출된 배정 기간 안인지 확인합니다.
            if (capturedAt.isBefore(pending.assignmentStartAt())
                    || !capturedAt.isBefore(pending.assignmentEndAt())) {
                return markInvalidAndRespond(
                        pending,
                        assignmentId,
                        "PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD",
                        capturedAt,
                        latitude,
                        longitude
                );
            }

            // 10. 공통 퀘스트 완료·보상 로직에서 기간과 멤버십을 다시 확인합니다.
            QuestCompletionResponse completion;
            try {
                completion = questCompletionService.completePhotoExifAssignment(
                        userId,
                        assignmentId
                );
            } catch (BusinessException exception) {
                if (exception.errorCode() == ErrorCode.QUEST_PERIOD_ENDED) {
                    return markInvalidAndRespond(
                            pending,
                            assignmentId,
                            "QUEST_PERIOD_ENDED",
                            capturedAt,
                            latitude,
                            longitude
                    );
                }
                throw exception;
            }

            // 11. 보상 완료 후에만 제출 이력을 VALID로 확정합니다.
            return markValidAndRespond(
                    pending,
                    assignmentId,
                    capturedAt,
                    latitude,
                    longitude,
                    completion.getCompletionId()
            );
        }
    }

    /**
     * 업로드 MIME과 최대 파일 크기를 확인합니다.
     */
    private void validateUpload(String contentType, byte[] jpegBytes) {
        if (!"image/jpeg".equalsIgnoreCase(contentType)) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "JPEG 파일만 업로드할 수 있습니다."
            );
        }

        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "업로드된 파일이 비어 있습니다."
            );
        }

        if (jpegBytes.length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    /**
     * 사용자·배정별 제출 수와 사용자별 분당 요청 수를 확인하고 PENDING 이력을 저장합니다.
     */
    private PendingPhoto createPendingSubmission(
            Long userId,
            Long assignmentId,
            String storageKey,
            Instant uploadedAt
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return transactionTemplate.execute(transactionStatus -> {
            // 같은 사용자의 동시 요청을 직렬화해 횟수 확인과 이력 저장을 원자적으로 처리합니다.
            User user = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.AUTH_REQUIRED,
                            "로그인이 필요합니다."
                    ));

            // 사진 접수 시점의 활성 멤버십을 확인합니다.
            membershipRepository.findActiveWithGroupByUserId(userId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.ROLE_FORBIDDEN,
                            "요청 권한이 없습니다."
                    ));

            QuestAssignment assignment =
                    questAssignmentRepository.findWithSnapshotById(assignmentId)
                            .orElseThrow(() -> new BusinessException(
                                    ErrorCode.RESOURCE_NOT_FOUND,
                                    "퀘스트 배정을 찾을 수 없습니다."
                            ));

            if (!"PHOTO_EXIF".equals(
                    assignment.getVerificationTypeSnapshot()
            )) {
                throw new BusinessException(
                        ErrorCode.QUEST_COMPLETION_NOT_ALLOWED,
                        "사진 EXIF 인증 퀘스트가 아닙니다."
                );
            }

            // 현재 시즌과 배정 기간을 확인합니다.
            Long currentSeasonId = seasonQueryService.findCurrentSeason().getId();
            boolean currentSeason = assignment.getQuestPeriod()
                    .getSeason()
                    .getId()
                    .equals(currentSeasonId);
            boolean activePeriod = "ACTIVE".equals(
                    assignment.getQuestPeriod().getStatus()
            ) && !uploadedAt.isBefore(assignment.getQuestPeriod().getStartAt())
                    && uploadedAt.isBefore(assignment.getQuestPeriod().getEndAt());

            if (!currentSeason || !activePeriod) {
                throw new BusinessException(ErrorCode.QUEST_PERIOD_ENDED);
            }

            // 실패 이력도 포함해 사용자·배정별 최대 5회인지 검사합니다.
            long attemptCount =
                    photoSubmissionRepository
                            .countByUser_IdAndQuestAssignment_Id(
                                    userId,
                                    assignmentId
                            );

            if (attemptCount >= 5) {
                throw new BusinessException(
                        ErrorCode.PHOTO_ATTEMPT_LIMIT_EXCEEDED
                );
            }

            // 업로드 시각 기준 직전 60초의 제출 수를 검사합니다.
            long requestsInLastMinute =
                    photoSubmissionRepository
                            .countByUser_IdAndUploadedAtGreaterThanEqualAndUploadedAtLessThan(
                                    userId,
                                    uploadedAt.minusSeconds(60),
                                    uploadedAt.plusNanos(1)
                            );

            if (requestsInLastMinute >= MAX_REQUESTS_PER_MINUTE) {
                throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
            }

            int attemptNumber = Math.toIntExact(attemptCount + 1);

            PhotoSubmission submission = photoSubmissionRepository.save(
                    PhotoSubmission.create(
                            user,
                            assignment,
                            storageKey,
                            attemptNumber,
                            uploadedAt
                    )
            );

            return new PendingPhoto(
                    submission.getId(),
                    assignmentId,
                    attemptNumber,
                    uploadedAt,
                    assignment.getQuestPeriod().getStartAt(),
                    assignment.getQuestPeriod().getEndAt()
            );
        });
    }

    /**
     * EXIF 촬영 시각을 Instant로 변환합니다.
     *
     * Offset이 없으면 KST로 해석합니다. 누락되었거나 형식이 잘못된 시각은 null을 반환합니다.
     */
    private Instant parseCapturedAt(String capturedAtRaw, String offsetRaw) {
        if (capturedAtRaw == null || capturedAtRaw.isBlank()) {
            return null;
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(
                    capturedAtRaw,
                    EXIF_DATE_TIME_FORMAT
            );

            if (offsetRaw == null || offsetRaw.isBlank()) {
                return localDateTime.atZone(KST).toInstant();
            }

            return localDateTime.toInstant(ZoneOffset.of(offsetRaw));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /**
     * 위도·경도가 허용 범위를 벗어났는지 확인합니다.
     */
    private boolean isCoordinateOutOfRange(
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        return latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0;
    }

    /**
     * 판정 실패 사유와 EXIF 값을 저장하고 응답 DTO를 만듭니다.
     */
    private PhotoSubmissionResponse markInvalidAndRespond(
            PendingPhoto pending,
            Long assignmentId,
            String failureReason,
            Instant capturedAt,
            BigDecimal latitude, // 정밀한 연산이 필요함 BigDecimal 사용
            BigDecimal longitude
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return transactionTemplate.execute(transactionStatus -> {
            PhotoSubmission submission = photoSubmissionRepository
                    .findById(pending.photoSubmissionId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "사진 제출 이력을 찾을 수 없습니다."
                    ));

            submission.markInvalid(
                    failureReason,
                    capturedAt,
                    latitude,
                    longitude,
                    Instant.now(clock)
            );

            return toResponse(submission, assignmentId, null);
        });
    }

    /**
     * 검증과 보상에 성공한 이력을 저장하고 응답 DTO를 만듭니다.
     */
    private PhotoSubmissionResponse markValidAndRespond(
            PendingPhoto pending,
            Long assignmentId,
            Instant capturedAt,
            BigDecimal latitude, // 정밀한 연산이 필요함 BigDecimal 사용
            BigDecimal longitude,
            Long completionId
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return transactionTemplate.execute(transactionStatus -> {
            PhotoSubmission submission = photoSubmissionRepository
                    .findById(pending.photoSubmissionId())
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "사진 제출 이력을 찾을 수 없습니다."
                    ));

            submission.markValid(
                    capturedAt,
                    latitude,
                    longitude,
                    Instant.now(clock)
            );

            return toResponse(submission, assignmentId, completionId);
        });
    }

    /**
     * 엔티티를 API 응답 DTO로 변환합니다.
     */
    private PhotoSubmissionResponse toResponse(
            PhotoSubmission submission,
            Long assignmentId,
            Long completionId
    ) {
        return new PhotoSubmissionResponse(
                submission.getId(),
                assignmentId,
                submission.getAttemptNumber(),
                submission.getVerificationStatus(),
                submission.getFailureReason(),
                submission.getUploadedAt(),
                submission.getCapturedAt(),
                completionId
        );
    }

    /**
     * PENDING 이력과 해당 배정의 기간 Snapshot입니다.
     */
    private record PendingPhoto(
            Long photoSubmissionId,
            Long assignmentId,
            int attemptNumber,
            Instant uploadedAt,
            Instant assignmentStartAt,
            Instant assignmentEndAt
    ) {
    }
}