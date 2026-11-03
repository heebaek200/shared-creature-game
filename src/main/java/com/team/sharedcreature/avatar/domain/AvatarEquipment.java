package com.team.sharedcreature.avatar.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * avatar_equipments 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "avatar_equipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AvatarEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "slot_code", length = 30, nullable = false)
    private String slotCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owned_item_id", nullable = false)
    private OwnedItem ownedItem;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
