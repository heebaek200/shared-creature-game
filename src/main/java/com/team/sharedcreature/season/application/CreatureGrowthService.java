package com.team.sharedcreature.season.application;

import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.infrastructure.CreatureExperienceLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 퀘스트 보상 트랜잭션 안에서 경험치 원장과 크리처 성장 상태를 함께 반영합니다. */
@Service
public class CreatureGrowthService {

    private final CreatureExperienceLedgerRepository ledgerRepository;
    private final CreatureUpdateEventPublisher eventPublisher;

    /** 경험치 원장과 Commit 이후 이벤트 경계를 주입받습니다. */
    public CreatureGrowthService(
            CreatureExperienceLedgerRepository ledgerRepository,
            CreatureUpdateEventPublisher eventPublisher
    ) {
        this.ledgerRepository = ledgerRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 호출자가 정해진 순서로 잠근 크리처에 퀘스트 경험치를 한 번만 반영합니다.
     *
     * @param creature 이미 비관적 잠금이 획득된 활성 크리처
     * @param completion 저장이 완료된 퀘스트 완료 행
     * @param amount 지급할 양수 경험치
     * @param occurredAt 보상 확정 시각
     * @return 반영 후 크리처 상태와 중복 여부
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GrowthResult applyQuestExperience(
            GroupCreature creature,
            QuestCompletion completion,
            long amount,
            Instant occurredAt
    ) {
        if (completion.getId() == null) {
            throw new IllegalArgumentException("Quest completion must be persisted before granting experience.");
        }
        if (ledgerRepository.findByQuestCompletionId(completion.getId()).isPresent()) {
            return GrowthResult.duplicate(creature);
        }

        creature.addExperience(amount, occurredAt);
        ledgerRepository.save(CreatureExperienceLedger.grant(
                creature,
                completion,
                amount,
                creature.getTotalExperience(),
                occurredAt
        ));
        eventPublisher.publishAfterCommit(new CreatureUpdatedEvent(
                creature.getGroupSeason().getGroup().getId(),
                creature.getId(),
                creature.getGrowthStage(),
                creature.getTotalExperience(),
                occurredAt
        ));
        return GrowthResult.applied(creature);
    }

    /** 보상 호출자에게 반영 후 단계·잔액과 중복 여부를 전달합니다. */
    public record GrowthResult(String stage, long totalExperience, boolean duplicate) {
        private static GrowthResult applied(GroupCreature creature) {
            return new GrowthResult(creature.getGrowthStage(), creature.getTotalExperience(), false);
        }

        private static GrowthResult duplicate(GroupCreature creature) {
            return new GrowthResult(creature.getGrowthStage(), creature.getTotalExperience(), true);
        }
    }
}
