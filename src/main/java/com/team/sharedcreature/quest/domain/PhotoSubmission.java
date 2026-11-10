package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * photo_submissions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "photo_submissions", uniqueConstraints =
        @UniqueConstraint(name = "uq_photo_submissions_user_assignment_attempt", columnNames = {"user_id", "quest_assignment_id", "attempt_number"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhotoSubmission {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_INVALID = "INVALID";

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

    /**
     * EXIF 검증을 시작할 제출 이력을 생성합니다.
     */
    public static PhotoSubmission create(
            User user,
            QuestAssignment questAssignment,
            String storageKey,
            int attemptNumber,
            Instant uploadedAt
    ) {
        // 1. 필수 연관 관계 및 값 객체 누락 방지 (Null Check)
        Objects.requireNonNull(user, "user는 필수입니다.");
        Objects.requireNonNull(questAssignment, "questAssignment는 필수입니다.");
        Objects.requireNonNull(uploadedAt, "uploadedAt은 필수입니다.");

        // 2. 비즈니스 규칙 및 제약 조건 검증
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber는 1~5여야 합니다.");
        }
        if (storageKey == null || storageKey.isBlank() || storageKey.length() > 500) {
            throw new IllegalArgumentException("storageKey는 1~500자여야 합니다.");
        }

        // 3. 초기 상태 설정 후 반환
        PhotoSubmission submission = new PhotoSubmission();
        submission.user = user;
        submission.questAssignment = questAssignment;
        submission.storageKey = storageKey;
        submission.attemptNumber = attemptNumber;
        submission.uploadedAt = uploadedAt;
        submission.verificationStatus = STATUS_PENDING; // 대기중
        submission.createdAt = uploadedAt;
        submission.updatedAt = uploadedAt;
        return submission;
    }

    /**
     * EXIF 검증 실패 결과를 이력에 기록합니다.
     * EXIF 누락이나 파싱 Timeout처럼 값이 없는 경우 capturedAt, latitude, longitude에 null을 전달할 수 있습니다.
     */
    public void markInvalid(
            String failureReason,
            Instant capturedAt,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant verifiedAt
    ) {
        requirePending(); // 현재 상태가 PENDING인지 선행 검증
        Objects.requireNonNull(verifiedAt, "verifiedAt은 필수입니다.");

        if (failureReason == null
                || failureReason.isBlank()
                || failureReason.length() > 50) {
            throw new IllegalArgumentException("failureReason은 1~50자여야 합니다.");
        }

        this.capturedAt = capturedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.verificationStatus = STATUS_INVALID; // 상태를 실패로 변경
        this.failureReason = failureReason;
        this.updatedAt = verifiedAt;
    }

    /**
     * EXIF 검증 성공 결과를 이력에 기록합니다.
     */
    public void markValid(
            Instant capturedAt,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant verifiedAt
    ) {
        requirePending(); // 현재 상태가 PENDING인지 선행 검증
        // 성공 시에는 모든 위치 및 시간 정보가 필수입니다.
        Objects.requireNonNull(capturedAt, "capturedAt은 필수입니다.");
        Objects.requireNonNull(latitude, "latitude는 필수입니다.");
        Objects.requireNonNull(longitude, "longitude는 필수입니다.");
        Objects.requireNonNull(verifiedAt, "verifiedAt은 필수입니다.");

        // 지구 좌표계 유효 범위 검증 (위도: -90~90, 경도: -180~180)
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("latitude는 -90~90 범위여야 합니다.");
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("longitude는 -180~180 범위여야 합니다.");
        }

        this.capturedAt = capturedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.verificationStatus = STATUS_VALID; // 상태를 성공으로 변경
        this.failureReason = null; // 성공했으므로 이전 실패 사유 초기화
        this.updatedAt = verifiedAt;
    }

    /**
     * 1시간 넘게 처리되지 않은 제출을 Timeout 실패로 전환합니다.
     */
    public void markPendingTimedOut(Instant failedAt) {
        requirePending();

        // 한 시간 안에 결과가 확정되지 않은 제출을 처리 실패로 종료합니다.
        markInvalid(
                "PHOTO_PROCESSING_FAILED",
                null,
                null,
                null,
                failedAt
        );
    }

    /**
     * 보존 기한이 지난 사진 파일과 GPS 좌표를 정리 완료 상태로 기록합니다.
     *
     * 촬영 시각과 판정 결과는 감사 이력으로 보존하고,
     * 파일 저장 키와 GPS 좌표만 비웁니다.
     */
    public void markPhotoAndLocationDataDeleted(Instant deletedAt) {
        Objects.requireNonNull(deletedAt, "deletedAt은 필수입니다.");

        if (!STATUS_VALID.equals(this.verificationStatus)
                && !STATUS_INVALID.equals(this.verificationStatus)) {
            throw new IllegalStateException(
                    "VALID 또는 INVALID 제출만 정리할 수 있습니다. status="
                            + this.verificationStatus
            );
        }

        // 이미 정리한 이력은 재호출해도 상태를 바꾸지 않습니다.
        if (this.fileDeletedAt != null) {
            return;
        }

        this.storageKey = null;
        this.latitude = null;
        this.longitude = null;
        this.fileDeletedAt = deletedAt;
        this.updatedAt = deletedAt;
    }

    /** 문지기(Guard) 메서드 */
    private void requirePending() {
        if (!STATUS_PENDING.equals(this.verificationStatus)) {
            throw new IllegalStateException(
                    "PENDING 상태의 제출만 판정할 수 있습니다. status="
                            + this.verificationStatus
            );
        }
    }
}
