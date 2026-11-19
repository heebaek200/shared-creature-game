package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * user_reports 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "user_reports", indexes = {
        @Index(name = "ix_user_reports_status_created", columnList = "status, created_at"),
        @Index(name = "ix_user_reports_reported_created", columnList = "reported_user_id, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_user_id", nullable = false)
    private User reporterUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id", nullable = false)
    private User reportedUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    /** 신고 분류는 기존 문자열 코드로 저장합니다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", length = 30, nullable = false)
    private UserReportCategory category;

    @Column(name = "content", length = 1000, nullable = false)
    private String content;

    /** 신고 상태는 이름 그대로 저장하며 접수 상태에서만 심사할 수 있습니다. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 30, nullable = false)
    private UserReportStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by_user_id")
    private User handledByUser;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "resolution", length = 1000)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 같은 그룹의 활성 회원 사이에서 접수된 신고를 생성합니다. */
    public static UserReport receive(
            User reporter,
            User reported,
            Group group,
            UserReportCategory category,
            String content,
            Instant now
    ) {
        UserReport report = new UserReport();
        report.reporterUser = reporter;
        report.reportedUser = reported;
        report.group = group;
        report.category = category;
        report.content = content;
        report.status = UserReportStatus.RECEIVED;
        report.createdAt = now;
        report.updatedAt = now;
        return report;
    }

    /** 접수 중인 신고를 기각하고 처리 사유를 보존합니다. */
    public void reject(User handler, String reason, Instant now) {
        requireReceived();
        status = UserReportStatus.REJECTED;
        handledByUser = handler;
        handledAt = now;
        resolution = reason;
        updatedAt = now;
    }

    /** 접수 중인 신고를 강퇴 조치 완료로 확정합니다. */
    public void resolve(User handler, String reason, Instant now) {
        requireReceived();
        status = UserReportStatus.RESOLVED;
        handledByUser = handler;
        handledAt = now;
        resolution = reason;
        updatedAt = now;
    }

    private void requireReceived() {
        if (status != UserReportStatus.RECEIVED) {
            throw new IllegalStateException("이미 처리된 신고입니다.");
        }
    }
}
