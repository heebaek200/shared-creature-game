package com.team.sharedcreature.quest.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * quest_completions 테이블의 상태와 이력을 보존하는 JPA 엔티티입니다.
 */
@Getter
@Entity
@Table(name = "quest_completions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quest_assignment_id", nullable = false)
    private QuestAssignment questAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_season_id", nullable = false)
    private GroupSeason groupSeason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id", nullable = false)
    private GroupMembership membership;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
