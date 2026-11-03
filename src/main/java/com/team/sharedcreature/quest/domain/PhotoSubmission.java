package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * photo_submissions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "photo_submissions", uniqueConstraints =
        @UniqueConstraint(name = "uq_photo_submissions_user_assignment_attempt", columnNames = {"user_id", "quest_assignment_id", "attempt_number"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhotoSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_assignment_id", nullable = false)
    private QuestAssignment questAssignment;

    @Column(name = "storage_key", length = 500, unique = true)
    private String storageKey;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "verification_status", length = 30, nullable = false)
    private String verificationStatus;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    @Column(name = "file_deleted_at")
    private Instant fileDeletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
