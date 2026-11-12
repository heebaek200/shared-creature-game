package com.team.sharedcreature.season.application;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupRankSnapshotRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * CLOSING 그룹 랭킹 Snapshot의 100건 배치 생성 규칙을 검증합니다.
 * 실제 도메인 엔티티와 Mockito Repository를 사용합니다.
 * DB Commit과 PESSIMISTIC_WRITE의 경합은 후속 MySQL 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonGroupSnapshotServiceTest {

    private static final long SEASON_ID = 91L;
    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @Mock SeasonRepository seasonRepository;
    @Mock GroupSeasonRepository groupSeasonRepository;
    @Mock SeasonGroupScoreRepository scoreRepository;
    @Mock SeasonGroupRankSnapshotRepository snapshotRepository;

    private SeasonGroupSnapshotService service;

    /**
     * 서비스와 Clock을 테스트마다 다시 구성합니다.
     * 외부 DB 접근 없이 Repository 호출만 검증합니다.
     * Snapshot 생성 시각은 고정 UTC를 사용합니다.
     */
    @BeforeEach
    void setUp() {
        service = new SeasonGroupSnapshotService(
                seasonRepository,
                groupSeasonRepository,
                scoreRepository,
                snapshotRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    /**
     * 0점 그룹의 Snapshot에 생성 시각 대체값과 연속 순위를 기록합니다.
     * 입력 순서가 뒤집혀 있어도 그룹 ID 순으로 순위가 계산됩니다.
     * 이미 저장한 Snapshot이 없는 최초 실행을 검증합니다.
     */
    @Test
    void zeroScoreGroupsAreSnapshottedWithDeterministicRanks() {
        Season season = closingSeason("ACTIVE");
        Fixture first = fixture(season, 2L, CREATED);
        Fixture second = fixture(season, 1L, CREATED);
        arrange(season, List.of(first, second));

        SeasonGroupSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isEqualTo(2);
        assertThat(progress.inserted()).isEqualTo(2);
        assertThat(progress.remaining()).isZero();

        ArgumentCaptor<SeasonGroupRankSnapshot> saved =
                ArgumentCaptor.forClass(SeasonGroupRankSnapshot.class);
        verify(snapshotRepository, times(2)).save(saved.capture());
        List<SeasonGroupRankSnapshot> snapshots = saved.getAllValues();

        assertThat(snapshots).extracting(SeasonGroupRankSnapshot::getRankNumber)
                .containsExactly(1, 2);
        assertThat(snapshots).extracting(s -> s.getGroup().getId())
                .containsExactly(1L, 2L);
        assertThat(snapshots).extracting(SeasonGroupRankSnapshot::getScoreReachedAt)
                .containsExactly(CREATED, CREATED);
        assertThat(snapshots).extracting(SeasonGroupRankSnapshot::getCreatedAt)
                .containsExactly(NOW, NOW);
    }

    /**
     * 이전 실행에서 만든 Snapshot은 다시 INSERT하지 않습니다.
     * 기존 순위와 점수 일관성을 확인한 뒤 완료를 반환합니다.
     * Batch가 반복돼도 생성 시각이 변하지 않음을 보장합니다.
     */
    @Test
    void existingSnapshotIsNotRewritten() {
        Season season = closingSeason("ACTIVE");
        Fixture fixture = fixture(season, 1L, CREATED);
        arrange(season, List.of(fixture));
        SeasonGroupRankSnapshot saved = SeasonGroupRankSnapshot.capture(
                season,
                fixture.groupSeason(),
                new SeasonRankingCalculator.GroupRank(1, 1L, 0L, CREATED),
                CREATED
        );
        when(snapshotRepository.findAllBySeasonId(SEASON_ID)).thenReturn(List.of(saved));

        SeasonGroupSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.inserted()).isZero();
        assertThat(progress.remaining()).isZero();
        verify(snapshotRepository, never()).save(any());
        assertThat(saved.getCreatedAt()).isEqualTo(CREATED);
    }

    /**
     * 만료된 SCHEDULED 시즌은 그룹 랭킹을 생성하지 않습니다.
     * 종료 출처를 읽은 다음 추가 조회를 수행하지 않습니다.
     * 초기화되지 않은 그룹의 점수도 요구하지 않습니다.
     */
    @Test
    void scheduledSeasonSkipsRankingSnapshots() {
        Season season = closingSeason("SCHEDULED");
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));

        assertThat(service.writeNextBatch(SEASON_ID).total()).isZero();

        verifyNoInteractions(groupSeasonRepository, scoreRepository, snapshotRepository);
    }

    /**
     * 그룹 시즌에 점수가 없다면 전체 배치를 중단합니다.
     * 일부 그룹의 Snapshot만 저장하는 부분 성공을 피합니다.
     * 종료 이전의 데이터 정합성 문제를 명확하게 드러냅니다.
     */
    @Test
    void missingGroupScoreAbortsBatch() {
        Season season = closingSeason("ACTIVE");
        Fixture fixture = fixture(season, 1L, CREATED);
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        when(groupSeasonRepository.findAllForSeasonSnapshot(SEASON_ID))
                .thenReturn(List.of(fixture.groupSeason()));
        when(scoreRepository.findAllForSeasonSnapshot(SEASON_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.writeNextBatch(SEASON_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 기존 Snapshot의 순위가 달라진 경우 원본을 변경하지 않습니다.
     * 기존 Snapshot의 불변성을 보호하기 위해 재실행을 실패시킵니다.
     * 새로운 Snapshot도 저장하지 않아야 합니다.
     */
    @Test
    void mismatchedExistingRankingFailsWithoutRewrite() {
        Season season = closingSeason("ACTIVE");
        Fixture fixture = fixture(season, 1L, CREATED);
        arrange(season, List.of(fixture));
        SeasonGroupRankSnapshot wrong = SeasonGroupRankSnapshot.capture(
                season,
                fixture.groupSeason(),
                new SeasonRankingCalculator.GroupRank(2, 1L, 0L, CREATED),
                CREATED
        );
        when(snapshotRepository.findAllBySeasonId(SEASON_ID)).thenReturn(List.of(wrong));

        assertThatThrownBy(() -> service.writeNextBatch(SEASON_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 한 트랜잭션에서 최대 100건만 새로 저장합니다.
     * 101개 그룹을 준비해 남은 한 건을 정확하게 계산합니다.
     * 다음 정상화 호출이 남은 데이터를 이어받을 수 있도록 합니다.
     */
    @Test
    void oneBatchWritesAtMostOneHundredSnapshots() {
        Season season = closingSeason("ACTIVE");
        List<Fixture> fixtures = new ArrayList<>();
        for (long id = 1; id <= 101; id++) {
            fixtures.add(fixture(season, id, CREATED));
        }
        arrange(season, fixtures);

        SeasonGroupSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isEqualTo(101);
        assertThat(progress.inserted()).isEqualTo(100);
        assertThat(progress.remaining()).isEqualTo(1);
        verify(snapshotRepository, times(100)).save(any(SeasonGroupRankSnapshot.class));
    }

    /**
     * 그룹 시즌과 점수 Repository의 조회 결과를 구성합니다.
     * 동일한 시즌 행을 배타 잠금으로 반환합니다.
     * 기존 Snapshot은 각 테스트에서 별도로 준비합니다.
     */
    private void arrange(Season season, List<Fixture> fixtures) {
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        when(groupSeasonRepository.findAllForSeasonSnapshot(SEASON_ID))
                .thenReturn(fixtures.stream().map(Fixture::groupSeason).toList());
        when(scoreRepository.findAllForSeasonSnapshot(SEASON_ID))
                .thenReturn(fixtures.stream().map(Fixture::score).toList());
    }

    /**
     * 보호된 JPA 생성자를 우회하는 테스트 전용 시즌 객체를 만듭니다.
     * 실제 종료 출처 판정 메서드를 사용하도록 CLOSING을 설정합니다.
     * 사용된 Reflection은 테스트 Fixture의 필드 설정에 한정됩니다.
     */
    private Season closingSeason(String closingFromStatus) {
        Season season = BeanUtils.instantiateClass(Season.class);
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", "CLOSING");
        ReflectionTestUtils.setField(season, "closingFromStatus", closingFromStatus);
        return season;
    }

    /**
     * 실제 그룹 시즌 및 0점 점수 도메인 객체를 생성합니다.
     * JPA의 식별자만 Reflection을 통해 테스트 값으로 지정합니다.
     * 모든 Fixture는 독립적인 그룹 ID와 그룹 시즌 ID를 사용합니다.
     */
    private Fixture fixture(Season season, long groupId, Instant createdAt) {
        Group group = Group.create("그룹" + groupId, "group" + groupId, "", mock(User.class), createdAt);
        ReflectionTestUtils.setField(group, "id", groupId);
        GroupSeason groupSeason = GroupSeason.start(group, season, createdAt);
        ReflectionTestUtils.setField(groupSeason, "id", groupId + 1000L);
        return new Fixture(groupSeason, SeasonGroupScore.zero(groupSeason, createdAt));
    }

    /**
     * 함께 저장할 그룹 시즌과 해당 점수를 묶습니다.
     * 두 엔티티의 관계가 서로 일치하는 상태로 생성됩니다.
     * 저장 후 그룹 랭킹의 참조 대상을 검증할 때 사용합니다.
     */
    private record Fixture(GroupSeason groupSeason, SeasonGroupScore score) {
    }
}
