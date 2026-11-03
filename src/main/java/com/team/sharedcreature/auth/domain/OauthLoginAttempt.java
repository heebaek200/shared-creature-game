package com.team.sharedcreature.auth.domain;

import com.team.sharedcreature.group.domain.GroupInviteLink;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * oauth_login_attempts 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "oauth_login_attempts", indexes =
        @Index(name = "ix_oauth_attempts_status_expires", columnList = "status, expires_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OauthLoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", length = 64, unique = true, nullable = false)
    private String stateHash;

    @Column(name = "initiating_session_hash", length = 64, nullable = false)
    private String initiatingSessionHash;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "intent", length = 30, nullable = false)
    private String intent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_link_id", nullable = true)
    private GroupInviteLink inviteLink;

    @Column(name = "return_route", length = 100, nullable = false)
    private String returnRoute;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private OauthLoginAttempt(
            String stateHash,
            String initiatingSessionHash,
            String provider,
            String intent,
            GroupInviteLink inviteLink,
            String returnRoute,
            Instant now
    ) {
        this.stateHash = stateHash;
        this.initiatingSessionHash = initiatingSessionHash;
        this.provider = provider;
        this.intent = intent;
        this.inviteLink = inviteLink;
        this.returnRoute = returnRoute;
        this.status = "PENDING";
        this.expiresAt = now.plusSeconds(600);
        this.createdAt = now;
    }

    /** 공급자 Redirect 전 일회성 OAuth 시도를 생성합니다. */
    public static OauthLoginAttempt start(
            String stateHash,
            String initiatingSessionHash,
            String provider,
            String intent,
            GroupInviteLink inviteLink,
            String returnRoute,
            Instant now
    ) {
        return new OauthLoginAttempt(stateHash, initiatingSessionHash, provider, intent, inviteLink, returnRoute, now);
    }

    /** 같은 세션에서 마지막으로 수락한 초대 문맥으로 교체합니다. */
    public void replaceInviteLink(GroupInviteLink latestInviteLink) {
        this.inviteLink = latestInviteLink;
    }

    /** 검증이 끝난 OAuth 시도를 한 번만 소비한 상태로 전환합니다. */
    public void consume(Instant now) {
        this.status = "CONSUMED";
        this.consumedAt = now;
    }
}
