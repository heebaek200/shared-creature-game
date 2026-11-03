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
@Table(name = "photo_submissions")
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

    @Column(name = "storage_key", length = 500, unique = true, nullable = false)
    private String storageKey;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "latitude", nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false)
    private BigDecimal longitude;

    @Column(name = "verification_status", length = 30, nullable = false)
    private String verificationStatus;

    @Column(name = "failure_reason", length = 50, nullable = false)
    private String failureReason;

    @Column(name = "file_deleted_at", nullable = false)
    private Instant fileDeletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
