package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 오래된 사진 제출 상태와 보존 기한이 지난 사진·좌표를 정리합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoSubmissionCleanupScheduler {

    /** 한 번의 실행에서 상태별로 처리할 최대 건수 */
    private static final int BATCH_SIZE = 100;

    /** 1시간 넘게 처리되지 않은 제출을 실패로 전환합니다. */
    private static final long PENDING_TIMEOUT_HOURS = 1;

    /** 실패 사진과 좌표의 보존 기간 */
    private static final long INVALID_RETENTION_HOURS = 24;

    /** 성공 사진과 좌표의 시즌 종료 후 보존 기간 */
    private static final long VALID_RETENTION_DAYS = 30;

    private final PhotoSubmissionRepository photoSubmissionRepository;
    private final PhotoFileStorage photoFileStorage;
    private final Clock clock;

    /**
     * 5분 간격으로 PENDING Timeout과 보존 기한이 지난 파일을 정리합니다.
     */
    @Scheduled(fixedDelayString = "${app.photo.cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupPhotoSubmissions() {
        Instant now = Instant.now(clock);

        // 1. 1시간 넘게 PENDING인 제출을 Timeout 실패로 기록합니다.
        List<PhotoSubmission> stalePending =
                photoSubmissionRepository.findStalePendingSubmissions(
                        now.minusSeconds(PENDING_TIMEOUT_HOURS * 60 * 60),
                        PageRequest.of(0, BATCH_SIZE)
                );

        for (PhotoSubmission submission : stalePending) {
            submission.markPendingTimedOut(now);
        }

        // 2. 실패한 지 24시간이 지난 사진과 좌표를 정리합니다.
        List<PhotoSubmission> expiredInvalid =
                photoSubmissionRepository.findInvalidSubmissionsForCleanup(
                        now.minusSeconds(INVALID_RETENTION_HOURS * 60 * 60),
                        PageRequest.of(0, BATCH_SIZE)
                );
        cleanupFilesAndCoordinates(expiredInvalid, now);

        // 3. 시즌 종료 후 30일이 지난 성공 사진과 좌표를 정리합니다.
        List<PhotoSubmission> expiredValid =
                photoSubmissionRepository.findValidSubmissionsForCleanup(
                        now.minusSeconds(VALID_RETENTION_DAYS * 24 * 60 * 60),
                        PageRequest.of(0, BATCH_SIZE)
                );
        cleanupFilesAndCoordinates(expiredValid, now);
    }

    /**
     * 파일 삭제가 성공한 이력의 storageKey와 GPS 좌표를 지웁니다.
     */
    private void cleanupFilesAndCoordinates(
            List<PhotoSubmission> submissions,
            Instant deletedAt
    ) {
        for (PhotoSubmission submission : submissions) {
            try {
                // 이미 파일이 없더라도 deleteIfExists가 정상 처리합니다.
                if (submission.getStorageKey() != null) {
                    photoFileStorage.delete(submission.getStorageKey());
                }

                // 파일 삭제 후에만 DB 이력의 저장 키와 좌표를 비웁니다.
                submission.markPhotoAndLocationDataDeleted(deletedAt);
            } catch (IOException exception) {
                // DB 이력을 미정리 상태로 두어 다음 실행에서 다시 시도합니다.
                log.warn(
                        "사진 파일 정리에 실패했습니다. submissionId={}",
                        submission.getId(),
                        exception
                );
            }
        }
    }
}