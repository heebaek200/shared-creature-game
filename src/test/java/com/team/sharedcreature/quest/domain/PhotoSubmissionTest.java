package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PhotoSubmissionTest {

    @Test
    void 오래된_PENDING은_PHOTO_PROCESSING_FAILED로_종료한다() {
        Instant uploadedAt = Instant.parse("2026-11-10T00:00:00Z");

        PhotoSubmission submission = PhotoSubmission.create(
                mock(User.class),
                mock(QuestAssignment.class),
                "test-photo.jpg",
                1,
                uploadedAt
        );

        submission.markPendingTimedOut(uploadedAt.plusSeconds(3601));

        assertThat(submission.getVerificationStatus()).isEqualTo("INVALID");
        assertThat(submission.getFailureReason())
                .isEqualTo("PHOTO_PROCESSING_FAILED");
    }
}