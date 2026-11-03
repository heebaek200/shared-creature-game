package com.team.sharedcreature.season.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * creature_species 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "creature_species")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatureSpecies {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, unique = true, nullable = false)
    private String code;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "asset_key", length = 255, nullable = false)
    private String assetKey;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "display_order", nullable = true)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
