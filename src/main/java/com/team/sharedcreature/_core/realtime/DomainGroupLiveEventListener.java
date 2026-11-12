package com.team.sharedcreature._core.realtime;

import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.avatar.application.AvatarEquipmentChangedEvent;
import com.team.sharedcreature.quest.application.ContributionUpdatedEvent;
import com.team.sharedcreature.season.application.CreatureUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Commit 이후 내부 도메인 이벤트를 계약된 그룹 STOMP 이벤트로 변환합니다. */
@Slf4j
@Component
public class DomainGroupLiveEventListener {

    private final GroupLiveEventPublisher eventPublisher;
    private final UserProfileRepository profileRepository;

    /** 그룹 이벤트 발행기와 기여 사용자 프로필 저장소를 주입받습니다. */
    public DomainGroupLiveEventListener(
            GroupLiveEventPublisher eventPublisher,
            UserProfileRepository profileRepository
    ) {
        this.eventPublisher = eventPublisher;
        this.profileRepository = profileRepository;
    }

    /** 크리처 변경을 `CREATURE_UPDATED` Payload로 전달합니다. */
    @EventListener
    public void onCreatureUpdated(CreatureUpdatedEvent event) {
        eventPublisher.publish(
                event.groupId(),
                "CREATURE_UPDATED",
                event.occurredAt(),
                new GroupLivePayloads.CreatureUpdated(
                        event.seasonId(),
                        event.groupCreatureId(),
                        event.experience(),
                        event.stage(),
                        event.creatureSpeciesId(),
                        event.occurredAt()
                )
        );
    }

    /** 아바타 장착 변경을 기존 Payload 그대로 그룹 토픽에 연결합니다. */
    @EventListener
    public void onAvatarEquipmentChanged(AvatarEquipmentChangedEvent event) {
        eventPublisher.publish(
                event.groupId(),
                "AVATAR_EQUIPMENT_CHANGED",
                event.occurredAt(),
                new GroupLivePayloads.AvatarEquipmentChanged(
                        event.userId(),
                        event.slot(),
                        event.equippedItem()
                )
        );
    }

    /** 기여도 변경에 현재 닉네임을 결합해 `CONTRIBUTION_UPDATED`로 전달합니다. */
    @EventListener
    public void onContributionUpdated(ContributionUpdatedEvent event) {
        profileRepository.findByUserId(event.userId()).ifPresentOrElse(
                profile -> eventPublisher.publish(
                        event.groupId(),
                        "CONTRIBUTION_UPDATED",
                        event.occurredAt(),
                        new GroupLivePayloads.ContributionUpdated(
                                event.userId(),
                                profile.getNickname(),
                                event.contributionDelta(),
                                event.contributionScore(),
                                event.creatureExperienceDelta(),
                                event.groupScore()
                        )
                ),
                () -> log.error(
                        "기여도 이벤트 사용자 프로필을 찾을 수 없습니다. userId={}",
                        event.userId()
                )
        );
    }
}
