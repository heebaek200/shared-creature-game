package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService.RetentionKind;
import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.season.domain.Season;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 사진 이력 트랜잭션의 상태 전이와 기간 재검증을 테스트합니다.
 * 이 테스트는 DB Mock으로 도메인 판단을 확인하며 실제 MySQL 검증은 4-H-2에서 수행합니다.
 * 서로 다른 Mock 설정을 thenReturn 인자 안에서 중첩하지 않습니다.
 */
class PhotoSubmissionCleanupTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:07:45Z");
    private static final String CLAIM = "11111111-1111-1111-1111-111111111111";
    private final PhotoSubmissionRepository repository = mock(PhotoSubmissionRepository.class);
    private final PhotoSubmissionCleanupTransactionService service =
            new PhotoSubmissionCleanupTransactionService(repository);

    /**
     * 1시간을 초과한 PENDING만 행 잠금으로 확인한 뒤 실패 상태로 변경합니다.
     * 실패 사유의 정확한 문자열은 PhotoSubmission 엔티티에서 정의합니다.
     * 완료한 변경을 트랜잭션 종료 전에 flush합니다.
     */
    @Test
    void marksPendingOlderThanOneHour() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(1L)).thenReturn(Optional.of(submission));
        when(submission.getVerificationStatus()).thenReturn("PENDING");
        when(submission.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(1)).minusNanos(1));

        assertTrue(service.expirePending(1L, NOW));
        verify(submission).markPendingTimedOut(NOW);
        verify(repository).flush();
    }

    /**
     * 정확히 1시간인 PENDING은 '1시간 초과' 조건에 해당하지 않습니다.
     * 여전히 진행 중인 EXIF 판정을 실패로 덮어쓰지 않습니다.
     * 저장소에 불필요한 flush도 발생하지 않아야 합니다.
     */
    @Test
    void keepsPendingAtExactOneHour() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(2L)).thenReturn(Optional.of(submission));
        when(submission.getVerificationStatus()).thenReturn("PENDING");
        when(submission.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(1)));

        assertFalse(service.expirePending(2L, NOW));
        verify(submission, never()).markPendingTimedOut(any());
        verify(repository, never()).flush();
    }

    /**
     * INVALID 사진은 실패 확정 후 다음 정리 실행에서 바로 삭제할 수 있습니다.
     * claim과 최종 확정은 서로 다른 짧은 트랜잭션을 사용합니다.
     * 삭제 완료 시 필요한 DB 상태 변경은 도메인 메서드에 위임합니다.
     */
    @Test
    void invalidFileIsEligibleOnNextCleanup() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(3L)).thenReturn(Optional.of(submission));
        when(submission.getVerificationStatus()).thenReturn("INVALID");
        when(submission.getId()).thenReturn(3L);
        when(submission.getStorageKey()).thenReturn("photo-key.jpg");
        when(submission.getCleanupClaimToken()).thenReturn(null, CLAIM);

        var candidate = service.claimEligibleFile(
                3L, RetentionKind.INVALID, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10))).orElseThrow();
        assertEquals("photo-key.jpg", candidate.storageKey());
        verify(submission).claimCleanup(CLAIM, NOW.plus(Duration.ofMinutes(10)));
        assertTrue(service.confirmFileDeletion(
                3L, RetentionKind.INVALID, "photo-key.jpg", CLAIM, NOW));
        verify(submission).markPhotoAndLocationDataDeleted(NOW);
    }

    /**
     * 시즌 종료 시각이 과거여도 CLOSING 동안에는 성공 사진을 삭제하지 않습니다.
     * 종료 Snapshot 확정을 기다리는 데이터까지 제거하지 않기 위한 제한입니다.
     * 현재 DB 상태를 조회한 뒤 파일 작업 대상에서 제외합니다.
     */
    @Test
    void validFileInClosingSeasonIsNotEligible() {
        PhotoSubmission submission = validSubmission(4L, "CLOSING", NOW.minus(Duration.ofDays(40)));
        when(repository.findForCleanupUpdate(4L)).thenReturn(Optional.of(submission));

        assertTrue(service.claimEligibleFile(
                4L, RetentionKind.VALID, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10))).isEmpty());
        verify(submission, never()).claimCleanup(anyString(), any());
    }

    /**
     * 시즌이 실제 ENDED이고 종료된 지 30일이 지났다면 성공 사진을 정리합니다.
     * 파일을 지운 뒤 최종 확정 시에도 동일한 조건을 확인합니다.
     * 정리 완료 시 크리처 성장·퀘스트 보상은 변경되지 않습니다.
     */
    @Test
    void validFileInEndedSeasonIsEligible() {
        PhotoSubmission submission = validSubmission(5L, "ENDED", NOW.minus(Duration.ofDays(30)));
        when(repository.findForCleanupUpdate(5L)).thenReturn(Optional.of(submission));
        when(submission.getCleanupClaimToken()).thenReturn(null, CLAIM);

        assertTrue(service.claimEligibleFile(
                5L, RetentionKind.VALID, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10))).isPresent());
        assertTrue(service.confirmFileDeletion(
                5L, RetentionKind.VALID, "test-photo.jpg", CLAIM, NOW));
        verify(submission).markPhotoAndLocationDataDeleted(NOW);
    }

    /**
     * 조회와 최종 확정 사이에 storageKey가 바뀌면 상태를 수정하지 않습니다.
     * 다른 실행자의 파일 작업 결과를 현재 실행이 잘못 확정하는 일을 막습니다.
     * 불일치 상태는 다음 점검에서 다시 판단할 수 있습니다.
     */
    @Test
    void refusesConfirmationWhenStorageKeyChanged() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(6L)).thenReturn(Optional.of(submission));
        when(submission.getVerificationStatus()).thenReturn("INVALID");
        when(submission.getStorageKey()).thenReturn("new-key.jpg");
        when(submission.getCleanupClaimToken()).thenReturn(CLAIM);

        assertFalse(service.confirmFileDeletion(
                6L, RetentionKind.INVALID, "old-key.jpg", CLAIM, NOW));
        verify(submission, never()).markPhotoAndLocationDataDeleted(any());
    }

    /** 유효한 기존 claim이 있으면 다른 실행이 같은 파일을 중복 삭제하도록 넘겨주지 않습니다. */
    @Test
    void activeClaimPreventsConcurrentFileCleanup() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(7L)).thenReturn(Optional.of(submission));
        when(submission.getVerificationStatus()).thenReturn("INVALID");
        when(submission.getCleanupClaimToken()).thenReturn("other-claim");
        when(submission.getCleanupClaimExpiresAt()).thenReturn(NOW.plus(Duration.ofMinutes(1)));

        assertTrue(service.claimEligibleFile(
                7L, RetentionKind.INVALID, CLAIM, NOW, NOW.plus(Duration.ofMinutes(10))).isEmpty());
        verify(submission, never()).claimCleanup(anyString(), any());
    }

    /** 파일 삭제 실패는 원래 판정 시각을 바꾸지 않고 5분 뒤 재시도로 전환합니다. */
    @Test
    void fileFailureReleasesClaimAndSchedulesRetry() {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        when(repository.findForCleanupUpdate(8L)).thenReturn(Optional.of(submission));
        when(submission.getCleanupClaimToken()).thenReturn(CLAIM);

        service.deferFileCleanup(8L, CLAIM, NOW.plus(Duration.ofMinutes(5)));

        verify(submission).deferCleanupUntil(NOW.plus(Duration.ofMinutes(5)));
        verify(repository).flush();
    }

    /**
     * 성공 사진과 시즌의 상태를 별도의 Mock으로 먼저 준비합니다.
     * Mock 생성/설정을 다른 Mock의 thenReturn 인자에 넣지 않습니다.
     * 실제 서비스는 JPA LAZY 연관을 읽을 수 있는 readOnly 트랜잭션에서 호출됩니다.
     */
    private PhotoSubmission validSubmission(Long id, String seasonStatus, Instant seasonEnd) {
        PhotoSubmission submission = mock(PhotoSubmission.class);
        QuestAssignment assignment = mock(QuestAssignment.class);
        QuestPeriod period = mock(QuestPeriod.class);
        Season season = mock(Season.class);
        when(submission.getId()).thenReturn(id);
        when(submission.getStorageKey()).thenReturn("test-photo.jpg");
        when(submission.getVerificationStatus()).thenReturn("VALID");
        when(submission.getQuestAssignment()).thenReturn(assignment);
        when(assignment.getQuestPeriod()).thenReturn(period);
        when(period.getSeason()).thenReturn(season);
        when(season.getStatus()).thenReturn(seasonStatus);
        lenient().when(season.getEndAt()).thenReturn(seasonEnd);
        return submission;
    }
}
