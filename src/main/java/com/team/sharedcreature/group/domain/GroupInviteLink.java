package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * group_invite_links 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "group_invite_links")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupInviteLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @Column(name = "token_hash", length = 64, unique = true, nullable = false)
    private String tokenHash;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_user_id", nullable = false)
    private User issuedByUser;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(
            name = "active_group_id",
            insertable = false,
            updatable = false,
            nullable = true
    )
    private Long activeGroupId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private GroupInviteLink(Group group, String tokenHash, User issuer, Instant now) {
        this.group = group;
        this.tokenHash = tokenHash;
        this.status = "ACTIVE";
        this.issuedByUser = issuer;
        this.issuedAt = now;
        this.createdAt = now;
    }

    /** 원문 Token을 저장하지 않는 활성 초대 링크를 생성합니다. */
    public static GroupInviteLink issue(Group group, String tokenHash, User issuer, Instant now) {
        return new GroupInviteLink(group, tokenHash, issuer, now);
    }

    /**
     * 그룹 종료 또는 초대 재발급으로 기존 링크를 무효화함
     *  - 기존 발급 이력은 보종
     *  - 활성 상태의 링크만 종료 시각을 기록함
     */
    public void invalidate(Instant now) {
        if (!"ACTIVE".equals(this.status)) {
            return;
        }

        // 기존 링크를 다시 사용할 수 없도록 처리함
        this.status = "INVALIDATE";
        this.invalidatedAt = now;
    }

}
