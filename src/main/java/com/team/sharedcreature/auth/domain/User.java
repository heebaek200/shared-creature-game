package com.team.sharedcreature.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * users 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "users", indexes =
        @Index(name = "ix_users_status_last_active", columnList = "status, last_active_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "global_role", length = 30, nullable = false)
    private String globalRole;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Column(name = "withdrawn_at", nullable = true)
    private Instant withdrawnAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private User(String globalRole, Instant now) {
        this.status = "ACTIVE";
        this.globalRole = globalRole;
        this.lastLoginAt = now;
        this.lastActiveAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 소셜 인증을 마친 신규 활성 회원을 생성합니다.
     *
     * @param globalRole 최초 전역 역할
     * @param now 가입 UTC 시각
     * @return 신규 회원
     */
    public static User register(String globalRole, Instant now) {
        return new User(globalRole, now);
    }

    /**
     * 기존 회원의 로그인 성공 시각과 마지막 인증 활동 시각을 갱신합니다.
     *
     * OAuth 인증이 성공한 기존 회원에게 적용하며, 신규 회원은 register()에서 초기화합니다.
     * 마지막 수정 시각도 함께 갱신하고 JPA Dirty Checking으로 DB에 반영합니다.
     *
     * @param now 로그인 성공 시각
     */
    public void recordLogin(Instant now) {
        // 로그인 성공과 동시에 두 인증 시각을 갱신합니다.
        this.lastLoginAt = now;
        this.lastActiveAt = now;
        this.updatedAt = now;
    }
}
