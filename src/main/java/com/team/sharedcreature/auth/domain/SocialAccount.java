package com.team.sharedcreature.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * social_accounts 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "social_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_social_accounts_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uq_social_accounts_provider_user", columnNames = {"provider", "provider_user_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "provider_user_id", length = 255, nullable = false)
    private String providerUserId;

    @Column(name = "email", length = 320, nullable = false)
    private String email;

    @Column(name = "normalized_email", length = 320, unique = true, nullable = false)
    private String normalizedEmail;

    @Column(name = "email_verified", nullable = true)
    private Boolean emailVerified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private SocialAccount(User user, String provider, String providerUserId, String email, boolean emailVerified, Instant now) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.normalizedEmail = email.strip().toLowerCase(java.util.Locale.ROOT);
        this.emailVerified = emailVerified;
        this.createdAt = now;
    }

    /** 검증된 공급자 계정을 내부 회원과 연결합니다. */
    public static SocialAccount link(
            User user,
            String provider,
            String providerUserId,
            String email,
            boolean emailVerified,
            Instant now
    ) {
        return new SocialAccount(user, provider, providerUserId, email, emailVerified, now);
    }
}
