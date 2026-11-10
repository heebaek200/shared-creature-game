package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 사진 제출 정리 Scheduler의 기한 처리와 파일 정리를 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class PhotoSubmissionCleanupSchedulerTest {

    private static final Instant NOW =
            Instant.parse("2026-11-10T01:00:00Z");

    @Mock
    private PhotoSubmissionRepository photoSubmissionRepository;

    @Mock
    private PhotoFileStorage photoFileStorage;

    @Mock
    private PhotoSubmission pendingSubmission;

    @Mock
    private PhotoSubmission invalidSubmission;

    @Mock
    private PhotoSubmission validSubmission;

    private PhotoSubmissionCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new PhotoSubmissionCleanupScheduler(
                photoSubmissionRepository,
                photoFileStorage,
                clock
        );
    }

    @Test
    void 한시간_지난_PENDING은_Timeout_실패로_전환하고_파일은_아직_삭제하지_않는다() {
        when(photoSubmissionRepository.findStalePendingSubmissions(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of(pendingSubmission));
        when(photoSubmissionRepository.findInvalidSubmissionsForCleanup(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of());
        when(photoSubmissionRepository.findValidSubmissionsForCleanup(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of());

        scheduler.cleanupPhotoSubmissions();

        verify(pendingSubmission).markPendingTimedOut(NOW);
        verifyNoInteractions(photoFileStorage);
    }

    @Test
    void 보존기한이_지난_INVALID와_VALID는_파일과_좌표_정리상태를_기록한다()
            throws Exception {
        when(photoSubmissionRepository.findStalePendingSubmissions(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of());
        when(photoSubmissionRepository.findInvalidSubmissionsForCleanup(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of(invalidSubmission));
        when(photoSubmissionRepository.findValidSubmissionsForCleanup(
                any(Instant.class),
                any(PageRequest.class)
        )).thenReturn(List.of(validSubmission));

        when(invalidSubmission.getStorageKey()).thenReturn("invalid-photo.jpg");
        when(validSubmission.getStorageKey()).thenReturn("valid-photo.jpg");

        scheduler.cleanupPhotoSubmissions();

        // 실패와 성공 사진 모두 파일을 삭제하고 좌표 정리 이력을 기록합니다.
        verify(photoFileStorage).delete("invalid-photo.jpg");
        verify(photoFileStorage).delete("valid-photo.jpg");
        verify(invalidSubmission).markPhotoAndLocationDataDeleted(NOW);
        verify(validSubmission).markPhotoAndLocationDataDeleted(NOW);
    }
}