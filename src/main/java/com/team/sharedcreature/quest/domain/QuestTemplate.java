package com.team.sharedcreature.quest.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_templates 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 30, unique = true, nullable = false)
    private String code;

    @Column(name = "period_type", length = 30, nullable = false)
    private String periodType;

    @Column(name = "difficulty", length = 30, nullable = false)
    private String difficulty;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "rotation_order", nullable = false)
    private Integer rotationOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
