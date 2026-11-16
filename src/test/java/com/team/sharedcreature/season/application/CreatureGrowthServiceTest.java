package com.team.sharedcreature.season.application;

import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.CreatureExperienceLedgerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatureGrowthServiceTest {

    @Test
    void 잠긴_크리처에_경험치와_원장을_반영하고_Commit_이후_이벤트를_예약한다() {
        CreatureExperienceLedgerRepository ledgerRepository = mock(CreatureExperienceLedgerRepository.class);
        CreatureUpdateEventPublisher eventPublisher = mock(CreatureUpdateEventPublisher.class);
        CreatureGrowthService service = new CreatureGrowthService(ledgerRepository, eventPublisher);
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(10L);
        GroupSeason groupSeason = mock(GroupSeason.class);
        when(groupSeason.getGroup()).thenReturn(group);
        Season season = mock(Season.class);
        when(season.getId()).thenReturn(20L);
        when(groupSeason.getSeason()).thenReturn(season);
        GroupCreature creature = GroupCreature.egg(groupSeason, Instant.parse("2026-11-03T00:00:00Z"));
        QuestCompletion completion = mock(QuestCompletion.class);
        when(completion.getId()).thenReturn(100L);
        when(ledgerRepository.findByQuestCompletionId(100L)).thenReturn(Optional.empty());

        CreatureGrowthService.GrowthResult result = service.applyQuestExperience(
                creature, completion, 45_000, Instant.parse("2026-11-03T01:00:00Z"));

        assertThat(result.totalExperience()).isEqualTo(45_000);
        assertThat(result.stage()).isEqualTo("EGG");
        assertThat(result.duplicate()).isFalse();
        verify(ledgerRepository).save(any());
        verify(eventPublisher).publishAfterCommit(any());
    }

    @Test
    void 같은_퀘스트_완료_원장이_있으면_경험치를_다시_지급하지_않는다() {
        CreatureExperienceLedgerRepository ledgerRepository = mock(CreatureExperienceLedgerRepository.class);
        CreatureUpdateEventPublisher eventPublisher = mock(CreatureUpdateEventPublisher.class);
        CreatureGrowthService service = new CreatureGrowthService(ledgerRepository, eventPublisher);
        GroupCreature creature = GroupCreature.egg(
                mock(GroupSeason.class), Instant.parse("2026-11-03T00:00:00Z"));
        QuestCompletion completion = mock(QuestCompletion.class);
        when(completion.getId()).thenReturn(100L);
        when(ledgerRepository.findByQuestCompletionId(100L)).thenReturn(Optional.of(mock()));

        CreatureGrowthService.GrowthResult result = service.applyQuestExperience(
                creature, completion, 100, Instant.parse("2026-11-03T01:00:00Z"));

        assertThat(result.duplicate()).isTrue();
        assertThat(creature.getTotalExperience()).isZero();
        verify(ledgerRepository, never()).save(any());
        verify(eventPublisher, never()).publishAfterCommit(any());
    }
}
