package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupSeasonTest {

    @Test
    void 시즌_참여는_준비_상태와_참여_시각부터_정확히_72시간인_선택_기한으로_시작한다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");

        GroupSeason groupSeason = GroupSeason.start(mock(Group.class), mock(Season.class), startedAt);

        assertThat(groupSeason.getStatus()).isEqualTo("PREPARING");
        assertThat(groupSeason.getSelectionDeadlineAt()).isEqualTo(Instant.parse("2026-11-06T00:00:00Z"));
    }

    @Test
    void 같은_종_재선택은_최초_선택_결과와_시각을_변경하지_않는다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");
        GroupSeason groupSeason = GroupSeason.start(mock(Group.class), mock(Season.class), startedAt);
        CreatureSpecies species = species(1L);
        User firstAdmin = mock(User.class);
        User secondAdmin = mock(User.class);
        Instant firstSelectedAt = startedAt.plusSeconds(60);

        assertThat(groupSeason.selectSpecies(species, firstAdmin, firstSelectedAt)).isTrue();
        assertThat(groupSeason.selectSpecies(species, secondAdmin, firstSelectedAt.plusSeconds(60))).isFalse();

        assertThat(groupSeason.getSelectedByUser()).isSameAs(firstAdmin);
        assertThat(groupSeason.getSelectedAt()).isEqualTo(firstSelectedAt);
    }

    @Test
    void 선택_기한과_정확히_같은_시각부터_종_선택을_거절한다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");
        GroupSeason groupSeason = GroupSeason.start(mock(Group.class), mock(Season.class), startedAt);

        assertThatThrownBy(() -> groupSeason.selectSpecies(
                species(1L), mock(User.class), groupSeason.getSelectionDeadlineAt()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 미선택_알은_무작위_종을_확정한_뒤에만_활성화된다() {
        Instant startedAt = Instant.parse("2026-11-03T00:00:00Z");
        GroupSeason groupSeason = GroupSeason.start(mock(Group.class), mock(Season.class), startedAt);
        Instant hatchedAt = groupSeason.getSelectionDeadlineAt();

        groupSeason.selectRandomSpecies(species(2L), hatchedAt);
        groupSeason.activate(hatchedAt);

        assertThat(groupSeason.getStatus()).isEqualTo("ACTIVE");
        assertThat(groupSeason.getSelectionSource()).isEqualTo("RANDOM");
        assertThat(groupSeason.getSelectedByUser()).isNull();
    }

    private CreatureSpecies species(Long id) {
        CreatureSpecies species = mock(CreatureSpecies.class);
        when(species.getId()).thenReturn(id);
        return species;
    }
}
