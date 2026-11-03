package com.team.sharedcreature.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * user_profiles 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "user_profiles", uniqueConstraints =
        @UniqueConstraint(name = "uq_user_profiles_user", columnNames = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "nickname", length = 20, nullable = false)
    private String nickname;

    @Column(name = "normalized_nickname", length = 20, unique = true, nullable = false)
    private String normalizedNickname;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UserProfile(User user, String nickname, String normalizedNickname, Instant now) {
        this.user = user;
        this.nickname = nickname;
        this.normalizedNickname = normalizedNickname;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 신규 회원의 화면 표시 프로필을 생성합니다. */
    public static UserProfile create(User user, String nickname, String normalizedNickname, Instant now) {
        return new UserProfile(user, nickname, normalizedNickname, now);
    }
}
