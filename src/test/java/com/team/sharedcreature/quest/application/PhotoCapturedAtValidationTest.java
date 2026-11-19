package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.PhotoFailureReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHOTO_EXIF 촬영 시각의 접수·멤버십·배정 기간 경계를 검증합니다.
 */
class PhotoCapturedAtValidationTest {

    private static final Instant MEMBERSHIP_JOINED_AT =
            Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant PERIOD_START_AT =
            Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant UPLOADED_AT =
            Instant.parse("2026-11-10T01:00:00Z");
    private static final Instant PERIOD_END_AT =
            Instant.parse("2026-11-11T00:00:00Z");

    @Test
    void 업로드_시각보다_미래에_촬영한_사진은_거절한다() {
        PhotoFailureReason failureReason = PhotoVerificationService.capturedAtFailureReason(
                UPLOADED_AT.plusSeconds(1),
                UPLOADED_AT,
                MEMBERSHIP_JOINED_AT,
                PERIOD_START_AT,
                PERIOD_END_AT
        );

        assertThat(failureReason).isEqualTo(PhotoFailureReason.PHOTO_CAPTURED_AFTER_UPLOAD);
    }

    @Test
    void 현재_멤버십_가입_전에_촬영한_사진은_거절한다() {
        PhotoFailureReason failureReason = PhotoVerificationService.capturedAtFailureReason(
                MEMBERSHIP_JOINED_AT.minusSeconds(1),
                UPLOADED_AT,
                MEMBERSHIP_JOINED_AT,
                PERIOD_START_AT.minusSeconds(3600),
                PERIOD_END_AT
        );

        assertThat(failureReason).isEqualTo(PhotoFailureReason.PHOTO_CAPTURED_BEFORE_MEMBERSHIP);
    }

    @Test
    void 업로드와_가입_시각의_경계값은_허용한다() {
        assertThat(PhotoVerificationService.capturedAtFailureReason(
                UPLOADED_AT,
                UPLOADED_AT,
                MEMBERSHIP_JOINED_AT,
                PERIOD_START_AT,
                PERIOD_END_AT
        )).isNull();

        assertThat(PhotoVerificationService.capturedAtFailureReason(
                MEMBERSHIP_JOINED_AT,
                UPLOADED_AT,
                MEMBERSHIP_JOINED_AT,
                PERIOD_START_AT,
                PERIOD_END_AT
        )).isNull();
    }

    @Test
    void 배정_종료_시각에_촬영한_사진은_기존과_같이_거절한다() {
        PhotoFailureReason failureReason = PhotoVerificationService.capturedAtFailureReason(
                PERIOD_END_AT,
                PERIOD_END_AT.plusSeconds(1),
                MEMBERSHIP_JOINED_AT,
                PERIOD_START_AT,
                PERIOD_END_AT
        );

        assertThat(failureReason)
                .isEqualTo(PhotoFailureReason.PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD);
    }
}
