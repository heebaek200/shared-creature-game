package com.team.sharedcreature.season.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GroupCreatureTest {

    @Test
    void 성장_임계값의_직전과_도달_시점이_정확한_단계로_계산된다() {
        assertThat(GroupCreature.stageFor(0)).isEqualTo("HATCHLING");
        assertThat(GroupCreature.stageFor(44_999)).isEqualTo("HATCHLING");
        assertThat(GroupCreature.stageFor(45_000)).isEqualTo("GROWING");
        assertThat(GroupCreature.stageFor(97_499)).isEqualTo("GROWING");
        assertThat(GroupCreature.stageFor(97_500)).isEqualTo("MATURE");
        assertThat(GroupCreature.stageFor(149_999)).isEqualTo("MATURE");
        assertThat(GroupCreature.stageFor(150_000)).isEqualTo("COMPLETE");
        assertThat(GroupCreature.stageFor(200_000)).isEqualTo("COMPLETE");
    }

    @Test
    void 알_기간에_누적한_경험치는_단계를_바꾸지_않고_부화할_때_최고_단계로_반영된다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");
        GroupCreature creature = GroupCreature.egg(mock(GroupSeason.class), startedAt);

        creature.addExperience(150_000, startedAt.plusSeconds(1));

        assertThat(creature.getGrowthStage()).isEqualTo("EGG");
        assertThat(creature.getTotalExperience()).isEqualTo(150_000);

        Instant hatchedAt = startedAt.plusSeconds(72L * 60 * 60);
        creature.hatch(mock(CreatureSpecies.class), "ADMIN", hatchedAt);

        assertThat(creature.getGrowthStage()).isEqualTo("COMPLETE");
        assertThat(creature.getGrowthStageReachedAt()).isEqualTo(hatchedAt);
    }

    @Test
    void 완전체_이후에도_경험치는_제한_없이_누적되고_단계는_역행하지_않는다() {
        Instant now = Instant.parse("2026-11-03T00:00:00Z");
        GroupCreature creature = GroupCreature.egg(mock(GroupSeason.class), now);
        creature.addExperience(150_000, now);
        creature.hatch(mock(CreatureSpecies.class), "ADMIN", now);

        creature.addExperience(50_000, now.plusSeconds(1));

        assertThat(creature.getGrowthStage()).isEqualTo("COMPLETE");
        assertThat(creature.getTotalExperience()).isEqualTo(200_000);
        assertThat(creature.nextStageThreshold()).isNull();
    }

    @Test
    void 경험치는_양수만_반영한다() {
        GroupCreature creature = GroupCreature.egg(
                mock(GroupSeason.class), Instant.parse("2026-11-03T00:00:00Z"));

        assertThatThrownBy(() -> creature.addExperience(0, Instant.parse("2026-11-03T00:00:01Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
