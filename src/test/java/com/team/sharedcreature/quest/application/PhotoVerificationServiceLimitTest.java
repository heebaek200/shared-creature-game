package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.api.QuestCompletionService;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestPeriod;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.Season;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private PhotoVerificationService service;
    private byte[] jpegBytes;

    @BeforeEach
    void setUp() throws Exception {
        // TransactionTemplate 콜백이 테스트에서 정상적으로 실행되도록 설정합니다.
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        photoProcessingLimiter = new PhotoProcessingLimiter();
        service = new PhotoVerificationService(
                photoSubmissionRepository,
                questAssignmentRepository,
                userRepository,
                membershipRepository,
                photoFileStorage,
                photoProcessingLimiter,
                new JpegExifReader(),
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

        when(assignment.getVerificationTypeSnapshot()).thenReturn("PHOTO_EXIF");
        when(assignment.getQuestPeriod()).thenReturn(period);
        when(period.getSeason()).thenReturn(season);
        when(period.getStatus()).thenReturn("ACTIVE");
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