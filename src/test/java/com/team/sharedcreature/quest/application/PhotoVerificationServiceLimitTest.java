package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestVerificationType;

import com.team.sharedcreature.quest.domain.QuestPeriodStatus;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.api.QuestCompletionService;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.quest.domain.PhotoSubmission;
import com.team.sharedcreature.quest.domain.PhotoFailureReason;
import com.team.sharedcreature.quest.domain.PhotoVerificationStatus;
import com.team.sharedcreature.quest.api.PhotoSubmissionResponse;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 사용자별 사진 제출 제한을 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class PhotoVerificationServiceLimitTest {

    private static final Instant NOW =
            Instant.parse("2026-11-10T01:00:00Z");

    @Mock
    private PhotoSubmissionRepository photoSubmissionRepository;

    @Mock
    private QuestAssignmentRepository questAssignmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GroupMembershipRepository membershipRepository;

    @Mock
    private PhotoFileStorage photoFileStorage;

    @Mock
    private QuestCompletionService questCompletionService;

    @Mock
    private SeasonQueryService seasonQueryService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private User user;

    @Mock
    private GroupMembership membership;

    @Mock
    private QuestAssignment assignment;

    @Mock
    private QuestPeriod period;

    @Mock
    private Season season;

    private PhotoProcessingLimiter photoProcessingLimiter;
    private JpegExifReader jpegExifReader;
    private PhotoVerificationService service;
    private byte[] jpegBytes;

    @BeforeEach
    void setUp() throws Exception {
        // TransactionTemplate 콜백이 테스트에서 정상적으로 실행되도록 설정합니다.
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        photoProcessingLimiter = spy(new PhotoProcessingLimiter());
        jpegExifReader = spy(new JpegExifReader());
        service = new PhotoVerificationService(
                photoSubmissionRepository,
                questAssignmentRepository,
                userRepository,
                membershipRepository,
                photoFileStorage,
                photoProcessingLimiter,
                jpegExifReader,
                questCompletionService,
                seasonQueryService,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        jpegBytes = createJpegWithoutExif();

        // 두 제한 테스트가 공통으로 사용하는 활성 사용자·멤버십·배정입니다.
        when(userRepository.findByIdForUpdate(7L)).thenReturn(
                java.util.Optional.of(user)
        );
        when(membershipRepository.findActiveWithGroupByUserId(7L))
                .thenReturn(java.util.Optional.of(membership));
        when(questAssignmentRepository.findWithSnapshotById(11L))
                .thenReturn(java.util.Optional.of(assignment));
        when(seasonQueryService.findCurrentSeason()).thenReturn(season);

        when(assignment.getVerificationTypeSnapshot()).thenReturn(QuestVerificationType.PHOTO_EXIF);
        when(assignment.getQuestPeriod()).thenReturn(period);
        when(period.getSeason()).thenReturn(season);
        when(period.getStatus()).thenReturn(QuestPeriodStatus.ACTIVE);
        when(period.getStartAt()).thenReturn(NOW.minusSeconds(3600));
        when(period.getEndAt()).thenReturn(NOW.plusSeconds(3600));
        when(season.getId()).thenReturn(4L);

        when(photoFileStorage.store(any(byte[].class)))
                .thenReturn("00000000-0000-0000-0000-000000000011.jpg");
    }

    @AfterEach
    void tearDown() {
        photoProcessingLimiter.shutdown();
    }

    @Test
    void 여섯번째_배정별_제출은_PHOTO_ATTEMPT_LIMIT_EXCEEDED로_거절한다() throws IOException {
        when(photoSubmissionRepository.countByUser_IdAndQuestAssignment_Id(
                7L,
                11L
        )).thenReturn(5L);

        assertThatThrownBy(() -> service.submit(
                7L,
                11L,
                "image/jpeg",
                jpegBytes
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.PHOTO_ATTEMPT_LIMIT_EXCEEDED));

        // 제한에 걸린 요청은 DB 이력에 저장되지 않고 미연결 파일은 제거합니다.
        verify(photoSubmissionRepository, never()).save(any());
        verify(photoFileStorage).delete(
                "00000000-0000-0000-0000-000000000011.jpg"
        );
    }

    @Test
    void 분당_여섯번째_요청은_RATE_LIMIT_EXCEEDED로_거절한다() throws IOException {
        when(photoSubmissionRepository.countByUser_IdAndQuestAssignment_Id(
                7L,
                11L
        )).thenReturn(0L);
        when(photoSubmissionRepository
                .countByUser_IdAndUploadedAtGreaterThanEqualAndUploadedAtLessThan(
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(Instant.class),
                        any(Instant.class)
                )).thenReturn(5L);

        assertThatThrownBy(() -> service.submit(
                7L,
                11L,
                "image/jpeg",
                jpegBytes
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) exception).errorCode())
                        .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));

        // 분당 제한도 제출 횟수에 넣지 않습니다.
        verify(photoSubmissionRepository, never()).save(any());
        verify(photoFileStorage).delete(
                "00000000-0000-0000-0000-000000000011.jpg"
        );
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void completedPhotoQuestIsRejectedBeforeWorkerReservationAndFileStorage() throws Exception {
        when(questCompletionService.isAssignmentCompleted(7L, 11L)).thenReturn(true);

        for (int request = 0; request < 3; request++) {
            assertThatThrownBy(() -> service.submit(7L, 11L, "image/jpeg", jpegBytes))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.errorCode()).isEqualTo(ErrorCode.QUEST_ALREADY_COMPLETED));
        }

        verify(photoProcessingLimiter, never()).reserve();
        verifyNoInteractions(photoFileStorage, photoSubmissionRepository, userRepository, transactionManager);
    }

    @Test
    void completedPhotoQuestDoesNotCreateAnotherAttempt() throws IOException {
        when(questCompletionService.findCompletionsByAssignmentId(7L, List.of(assignment)))
                .thenReturn(Map.of(11L, mock(QuestCompletion.class)));

        assertThatThrownBy(() -> service.submit(7L, 11L, "image/jpeg", jpegBytes))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.QUEST_ALREADY_COMPLETED));

        verify(photoSubmissionRepository, never()).save(any());
        verify(photoSubmissionRepository, never()).countByUser_IdAndQuestAssignment_Id(7L, 11L);
        verify(photoFileStorage).delete("00000000-0000-0000-0000-000000000011.jpg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026:02:30 12:00:00", "2026:11:09 24:00:00", "2025:02:29 12:00:00"})
    void invalidExifDatesAreRejectedWithoutReward(String capturedAt) throws Exception {
        PhotoSubmission submission = givenPendingSubmission();
        doReturn(new JpegExifReader.ExifData(
                BigDecimal.ONE, BigDecimal.ONE, capturedAt, null))
                .when(jpegExifReader).readExif(jpegBytes);

        PhotoSubmissionResponse response = service.submit(7L, 11L, "image/jpeg", jpegBytes);

        assertThat(response.verificationStatus()).isEqualTo(PhotoVerificationStatus.INVALID);
        assertThat(submission.getFailureReason()).isEqualTo(PhotoFailureReason.EXIF_CAPTURED_AT_MISSING);
        verify(questCompletionService, never()).completePhotoExifAssignment(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "2026:11:10 09:59:59, , 2026-11-10T00:59:59Z",
            "2026:11:10 09:59:59, +09:00, 2026-11-10T00:59:59Z",
            "2026:11:10 00:59:59, Z, 2026-11-10T00:59:59Z"
    })
    void validDatesPreserveKstAndExplicitOffsets(String capturedAt, String offset, String expected) throws Exception {
        givenPendingSubmission();
        doReturn(new JpegExifReader.ExifData(
                BigDecimal.ONE, BigDecimal.ONE, capturedAt, offset))
                .when(jpegExifReader).readExif(jpegBytes);

        PhotoSubmissionResponse response = service.submit(7L, 11L, "image/jpeg", jpegBytes);

        assertThat(response.verificationStatus()).isEqualTo(PhotoVerificationStatus.VALID);
        assertThat(response.capturedAt()).isEqualTo(Instant.parse(expected));
        verify(questCompletionService).completePhotoExifAssignment(
                7L, 11L, 91L, Instant.parse(expected), BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void malformedGpsIsRecordedAsInvalidWithoutReward() throws Exception {
        PhotoSubmission submission = givenPendingSubmission();
        doThrow(new JpegExifReader.InvalidGpsException()).when(jpegExifReader).readExif(jpegBytes);

        PhotoSubmissionResponse response = service.submit(7L, 11L, "image/jpeg", jpegBytes);

        assertThat(response.failureReason()).isEqualTo("EXIF_GPS_OUT_OF_RANGE");
        assertThat(submission.getVerificationStatus()).isEqualTo(PhotoVerificationStatus.INVALID);
        assertThat(submission.getLatitude()).isNull();
        verify(questCompletionService, never()).completePhotoExifAssignment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void oversizedGpsValueIsRejectedWithoutPersistingAnUnrepresentableCoordinate() throws Exception {
        PhotoSubmission submission = givenPendingSubmission();
        doReturn(new JpegExifReader.ExifData(
                new BigDecimal("1000000000"), BigDecimal.ONE, "2026:11:10 09:59:59", null))
                .when(jpegExifReader).readExif(jpegBytes);

        PhotoSubmissionResponse response = service.submit(7L, 11L, "image/jpeg", jpegBytes);

        assertThat(response.failureReason()).isEqualTo("EXIF_GPS_OUT_OF_RANGE");
        assertThat(submission.getLatitude()).isNull();
        assertThat(submission.getLongitude()).isNull();
        verify(questCompletionService, never()).completePhotoExifAssignment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentCompletionLeavesARejectedAuditRecord() throws Exception {
        PhotoSubmission submission = givenPendingSubmission();
        Instant capturedAt = NOW.minusSeconds(1);
        doReturn(new JpegExifReader.ExifData(
                BigDecimal.ONE, BigDecimal.ONE, "2026:11:10 09:59:59", null))
                .when(jpegExifReader).readExif(jpegBytes);
        when(questCompletionService.completePhotoExifAssignment(
                7L, 11L, 91L, capturedAt, BigDecimal.ONE, BigDecimal.ONE))
                .thenThrow(new BusinessException(ErrorCode.QUEST_ALREADY_COMPLETED));

        assertThatThrownBy(() -> service.submit(7L, 11L, "image/jpeg", jpegBytes))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.QUEST_ALREADY_COMPLETED));

        assertThat(submission.getVerificationStatus()).isEqualTo(PhotoVerificationStatus.INVALID);
        assertThat(submission.getFailureReason()).isEqualTo(PhotoFailureReason.QUEST_ALREADY_COMPLETED);
    }

    /** 실제 엔티티로 PENDING 생성과 실패 결과 저장을 검증합니다. */
    private PhotoSubmission givenPendingSubmission() {
        PhotoSubmission submission = PhotoSubmission.create(
                user, assignment, "00000000-0000-0000-0000-000000000011.jpg", 1, NOW);
        ReflectionTestUtils.setField(submission, "id", 91L);
        when(photoSubmissionRepository.save(any(PhotoSubmission.class))).thenReturn(submission);
        // 정상 인증 경로는 실패 이력 재조회를 호출하지 않습니다.
        lenient().when(photoSubmissionRepository.findById(91L)).thenReturn(Optional.of(submission));
        when(membership.getJoinedAt()).thenReturn(NOW.minusSeconds(3600));
        return submission;
    }

    /**
     * ImageIO로 EXIF 없는 테스트용 JPEG를 생성합니다.
     */
    private byte[] createJpegWithoutExif() throws Exception {
        BufferedImage image = new BufferedImage(
                1,
                1,
                BufferedImage.TYPE_INT_RGB
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if (!ImageIO.write(image, "jpg", output)) {
            throw new IllegalStateException("테스트 JPEG 생성에 실패했습니다.");
        }

        return output.toByteArray();
    }
}
