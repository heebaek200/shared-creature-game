package com.team.sharedcreature.quest.api;

import java.time.Instant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 퀘스트 완료와 보상 반영 결과를 응답합니다.
 */
@Getter
@RequiredArgsConstructor
public class QuestCompletionResponse {

    /** 생성되거나 기존 결과로 반환된 완료 기록 ID입니다. */
    private final Long completionId;

    /** 완료한 퀘스트 배정 ID입니다. */
    private final Long assignmentId;

    /** 완료한 사용자 ID입니다. */
    private final Long userId;

    /** 완료 확정 시각입니다. */
    private final Instant completedAt;

    /** 이번 완료로 지급한 크리처 경험치입니다. */
    private final Long creatureExperienceAwarded;

    /** 이번 완료로 지급한 개인 기여도입니다. */
    private final Long contributionAwarded;

    /** 보상 반영 뒤 크리처 누적 경험치입니다. */
    private final Long creatureTotalExperience;

    /** 보상 반영 뒤 크리처 성장 단계입니다. */
    private final String creatureGrowthStage;

    /** 보상 반영 뒤 그룹 시즌 점수입니다. */
    private final Long groupSeasonScore;
}