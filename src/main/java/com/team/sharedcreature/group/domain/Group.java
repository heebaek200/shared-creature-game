package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * game_groups 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "game_groups", indexes =
        @Index(name = "ix_game_groups_public_list", columnList = "status, visibility, current_member_count"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 30, nullable = false)
    private String name;

    @Column(name = "normalized_name", length = 30, unique = true, nullable = false)
    private String normalizedName;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "visibility", length = 30, nullable = false)
    private String visibility;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "current_member_count", nullable = false)
    private Integer currentMemberCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "primary_admin_user_id", nullable = true)
    private User primaryAdminUser;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Group(String name, String normalizedName, String description, User creator, Instant now) {
        this.name = name;
        this.normalizedName = normalizedName;
        this.description = description;
        this.visibility = "PUBLIC";
        this.status = "ACTIVE";
        this.capacity = 20;
        this.currentMemberCount = 1;
        this.primaryAdminUser = creator;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 신규 공개 그룹과 최초 주 관리자를 생성합니다. */
    public static Group create(String name, String normalizedName, String description, User creator, Instant now) {
        return new Group(name, normalizedName, description, creator, now);
    }

    /**
     * 그룹에 연결된 주 관리자 사용자 참조를 변경합니다.
     *  - 멤버십의 PRIMARY 역할 변경과 동일한 트랜잭션에서 호출해야 함
     *  - 그룹의 최신 수정 시간도 함께 갱신함
     */
    public void changePrimaryAdmin(User nextPrimary, Instant now) {
        if (nextPrimary == null) {
            throw new IllegalArgumentException("주 관리자가 필요합니다.");
        }

        this.primaryAdminUser = nextPrimary;
        this.updatedAt = now;
    }

    /**
     * 활성 그룹에서 일반 회원 한 명의 탈퇴를 반영합니다.
     *  - 주 관리자(PRIMARY)가 남아 있는 그룹에서만 진행
     *  - 마지막 구성원 탈퇴는 다른 기능 deactivateAfterLastMemberLeaves 에서 수행
     */
    public void decrementMemberCount(Instant now) {
        if (!"ACTIVE".equals(this.status) || this.currentMemberCount <= 1) {
            throw new IllegalStateException("그룹 인원을 감소시킬 수 없습니다.");
        }

        // 관리 중인 그룹의 인원만 감소합니다.
        this.currentMemberCount --;
        this.updatedAt = now;
    }

    /**
     * 유일한 주 관리자(PRIMARY) 탈퇴로 그룹을 비활성화합니다.
     *  - 구성원이 정확히 한 명 남은 활성 그룹에서만 사용할 수 있음
     *  - 그룹 데이터는 유지하고 관리자 참조만 해제함
     */
    public void deactivateAfterLastMemberLeaves(Instant now) {
        if (!"ACTIVE".equals(this.status) || this.currentMemberCount != 1) {
            throw new IllegalStateException("1인 활성 그룹만 종료할 수 있습니다.");
        }

        // 그룹 종료와 함께 인원 및 주 관리자 참조를 정리
        this.status = "INACTIVE";
        this.currentMemberCount = 0;
        this.primaryAdminUser = null;
        this.updatedAt = now;
    }

}
