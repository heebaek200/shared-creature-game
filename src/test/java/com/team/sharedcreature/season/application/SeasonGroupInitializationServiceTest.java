package com.team.sharedcreature.season.application;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SCHEDULED 시즌의 그룹·알·점수 초기화 단위 테스트입니다.
 * 최초 실제 초기화 시각부터의 72시간과 부분 복구의 시각 보존을 확인합니다.
 * DB 행 잠금과 실제 Commit의 동시성 검증은 통합 테스트에서 수행합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonGroupInitializationServiceTest {

    private static final Long SEASON_ID = 77L;
    private static final Instant START = Instant.parse("2026-11-01T15:00:00Z");
    private static final Instant END = Instant.parse("2026-11-29T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant ORIGINAL = Instant.parse("2026-11-04T01:00:00Z");

    @Mock private SeasonRepository seasonRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupSeasonRepository groupSeasonRepository;
    @Mock private GroupCreatureRepository creatureRepository;
    @Mock private SeasonGroupScoreRepository scoreRepository;

    private MutableClock clock;
    private SeasonGroupInitializationService service;

    /**
     * 테스트마다 변경 가능한 Clock과 신규 Service를 준비합니다.
     * 테스트 대상 Repository는 모두 Mock으로 구성합니다.
     * 서로 다른 테스트의 시간을 공유하지 않도록 인스턴스를 격리합니다.
     */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new SeasonGroupInitializationService(
                seasonRepository, groupRepository, groupSeasonRepository,
                creatureRepository, scoreRepository, clock
        );
    }

    /**
     * 새로운 활성 그룹의 시즌 참여·알·0점 집계를 한 번씩 생성합니다.
     * GroupSeason의 실제 생성 시각을 부화 기한의 기준으로 삼습니다.
     * 생성 시각과 72시간 기한이 정확하게 저장되는지 검증합니다.
     */
    @Test
    void createsNewGroupSeasonWithFullSeventyTwoHours() {
        arrangeSeason("SCHEDULED");
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(group(1)));
        stubSavedGroupSeasons();

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.COMPLETE, result.outcome());
        assertEquals(1, result.processedGroups());
        assertEquals(1, result.createdGroupSeasons());
        assertEquals(1, result.createdEggs());
        assertEquals(1, result.createdScores());

        // 첫 생성 시간이 시즌 달력 시작이 아니라 실제 현재 시각이어야 합니다.
        ArgumentCaptor<GroupSeason> seasonCaptor = ArgumentCaptor.forClass(GroupSeason.class);
        verify(groupSeasonRepository).saveAndFlush(seasonCaptor.capture());
        assertEquals(NOW, seasonCaptor.getValue().getCreatedAt());
        assertEquals(NOW.plusSeconds(72L * 3600L), seasonCaptor.getValue().getSelectionDeadlineAt());

        ArgumentCaptor<GroupCreature> creatureCaptor = ArgumentCaptor.forClass(GroupCreature.class);
        verify(creatureRepository).save(creatureCaptor.capture());
        assertEquals("EGG", creatureCaptor.getValue().getGrowthStage());
        assertEquals(NOW, creatureCaptor.getValue().getCreatedAt());
        assertNull(creatureCaptor.getValue().getHatchedAt());

        ArgumentCaptor<SeasonGroupScore> scoreCaptor = ArgumentCaptor.forClass(SeasonGroupScore.class);
        verify(scoreRepository).save(scoreCaptor.capture());
        assertEquals(0L, scoreCaptor.getValue().getScore());
        assertNull(scoreCaptor.getValue().getScoreReachedAt());
    }

    /**
     * 누락된 자식 행을 복구하더라도 최초 그룹 시즌 생성 시각은 유지합니다.
     * 기존 groupSeason의 selectionDeadlineAt을 다시 계산하거나 수정하지 않습니다.
     * 누락된 알과 점수는 최초 생성 시각을 사용해 복원합니다.
     */
    @Test
    void repairsMissingChildrenWithoutResettingDeadline() {
        Season season = arrangeSeason("SCHEDULED");
        Group group = group(2);
        GroupSeason existing = GroupSeason.start(group, season, ORIGINAL);
        ReflectionTestUtils.setField(existing, "id", 1002L);
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), any())).thenReturn(List.of(group));
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(2L, SEASON_ID))
                .thenReturn(Optional.of(existing));

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(0, result.createdGroupSeasons());
        assertEquals(1, result.createdEggs());
        assertEquals(1, result.createdScores());
        assertEquals(ORIGINAL.plusSeconds(72L * 3600L), existing.getSelectionDeadlineAt());
        verify(groupSeasonRepository, never()).saveAndFlush(any());

        // 복구된 행의 생성 시각도 이미 기록된 최초 시각을 사용합니다.
        ArgumentCaptor<GroupCreature> eggs = ArgumentCaptor.forClass(GroupCreature.class);
        ArgumentCaptor<SeasonGroupScore> scores = ArgumentCaptor.forClass(SeasonGroupScore.class);
        verify(creatureRepository).save(eggs.capture());
        verify(scoreRepository).save(scores.capture());
        assertEquals(ORIGINAL, eggs.getValue().getCreatedAt());
        assertEquals(ORIGINAL, scores.getValue().getCreatedAt());
    }

    /**
     * 기존 알과 점수가 존재하는데도 반복 생성하지 않아야 합니다.
     * 초기화 누락 대상에 일시적으로 포함된 그룹이라도 기존 데이터를 유지합니다.
     * 부화 기한과 점수 행을 불필요하게 갱신하지 않습니다.
     */
    @Test
    void existingChildrenAreNeverRecreated() {
        Season season = arrangeSeason("SCHEDULED");
        Group group = group(3);
        GroupSeason existing = GroupSeason.start(group, season, ORIGINAL);
        ReflectionTestUtils.setField(existing, "id", 1003L);
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), any())).thenReturn(List.of(group));
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(3L, SEASON_ID))
                .thenReturn(Optional.of(existing));
        when(creatureRepository.findSnapshotByGroupSeasonId(1003L))
                .thenReturn(Optional.of(GroupCreature.egg(existing, ORIGINAL)));
        when(scoreRepository.findByGroupSeasonId(1003L))
                .thenReturn(Optional.of(SeasonGroupScore.zero(existing, ORIGINAL)));

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(0, result.createdGroupSeasons());
        assertEquals(0, result.createdEggs());
        assertEquals(0, result.createdScores());
        verify(groupSeasonRepository, never()).saveAndFlush(any());
        verify(creatureRepository, never()).save(any());
        verify(scoreRepository, never()).save(any());
    }

    /**
     * 시즌 종료 시각에 도달한 SCHEDULED는 그룹을 초기화하지 않습니다.
     * 서버가 오래 꺼져 있던 동안의 알을 새로 만들지 않는 규칙입니다.
     * 그룹 조회 자체를 생략하여 과거 데이터의 변경을 방지합니다.
     */
    @Test
    void expiredScheduledSeasonDoesNotCreateEggs() {
        arrangeSeason("SCHEDULED");
        clock.set(END);

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.EXPIRED, result.outcome());
        verifyNoInteractions(groupRepository, groupSeasonRepository, creatureRepository, scoreRepository);
    }

    /**
     * ACTIVE 전환 이후에는 SCHEDULED 전용 초기화 작업을 실행하지 않습니다.
     * 이미 사용 중인 크리처나 점수를 덮어쓰지 않습니다.
     * 후속 정상화 반복 시 조용히 건너뛰도록 합니다.
     */
    @Test
    void activeSeasonIsNotInitialized() {
        arrangeSeason("ACTIVE");

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.NOT_SCHEDULED, result.outcome());
        verifyNoInteractions(groupRepository, groupSeasonRepository, creatureRepository, scoreRepository);
    }

    /**
     * 그룹 초기화 대상이 없으면 배치를 완료로 표시합니다.
     * 아무런 INSERT나 기존 데이터 변경을 수행하지 않습니다.
     * 다음 단계의 최종 활성화 검사는 별도로 수행해야 합니다.
     */
    @Test
    void noMissingGroupsReturnsComplete() {
        arrangeSeason("SCHEDULED");
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), any())).thenReturn(List.of());

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.COMPLETE, result.outcome());
        assertEquals(0, result.processedGroups());
        verifyNoInteractions(groupSeasonRepository, creatureRepository, scoreRepository);
    }

    /**
     * 최대 100개 그룹만 처리한 뒤 다음 배치에서 재조회하도록 합니다.
     * 여러 서버가 반복 실행해도 동일한 첫 100개가 계속 선택되지 않아야 합니다.
     * DB에서는 완료된 그룹을 원래 후보 쿼리에서 제외합니다.
     */
    @Test
    void hundredGroupsRequireAnotherCheck() {
        arrangeSeason("SCHEDULED");
        List<Group> groups = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            groups.add(group(id));
        }
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), eq(PageRequest.of(0, 100)))).thenReturn(groups);
        stubSavedGroupSeasons();

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.MORE_MAY_REMAIN, result.outcome());
        assertEquals(100, result.processedGroups());
        assertEquals(100, result.createdGroupSeasons());
        assertEquals(100, result.createdEggs());
        assertEquals(100, result.createdScores());
        verify(groupSeasonRepository, times(100)).saveAndFlush(any(GroupSeason.class));
    }

    /**
     * DB Group 잠금 대기 중에 시즌이 만료되면 초기화를 시작하지 않습니다.
     * 그룹 후보를 반환하기 직전에 Clock을 종료 시각으로 변경합니다.
     * 날짜 경계 이전에 선조회한 시간을 재사용하면 안 됩니다.
     */
    @Test
    void expirationDuringGroupLockSkipsWholeBatch() {
        arrangeSeason("SCHEDULED");
        when(groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                eq(SEASON_ID), any())).thenAnswer(invocation -> {
                    clock.set(END);
                    return List.of(group(1));
                });

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.EXPIRED, result.outcome());
        verifyNoInteractions(groupSeasonRepository, creatureRepository, scoreRepository);
    }

    /**
     * 첫 시즌이 시작되기 이전에는 그룹을 준비하지 않습니다.
     * 아직 존재하지 않는 기간의 알을 미리 생성하는 것을 막습니다.
     * Clock 경계 검사를 통해 NOT_STARTED를 반환합니다.
     */
    @Test
    void beforeStartSkipsGroupInitialization() {
        arrangeSeason("SCHEDULED");
        clock.set(START.minusSeconds(1));

        SeasonGroupInitializationService.BatchResult result = service.initializeNextBatch(SEASON_ID);

        assertEquals(SeasonGroupInitializationService.Outcome.NOT_STARTED, result.outcome());
        verifyNoInteractions(groupRepository, groupSeasonRepository, creatureRepository, scoreRepository);
    }

    /**
     * 그룹 초기화 서비스에 넘길 실제 SCHEDULED 도메인 엔티티를 생성합니다.
     * protected JPA 생성자를 직접 호출하지 않기 위해 schedule() 팩토리를 재사용합니다.
     * 상태를 변경하는 테스트는 Reflection을 이용해 상태만 지정합니다.
     */
    private Season arrangeSeason(String status) {
        Season season = Season.schedule(2, START, END, NOW);
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", status);
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        return season;
    }

    /**
     * 그룹 도메인 팩토리를 사용하여 테스트용 활성 그룹을 생성합니다.
     * ID는 JPA 저장소 없이 Test Fixture로만 지정합니다.
     * 실제 그룹명이나 구성원 저장은 이 단위 테스트의 범위가 아닙니다.
     */
    private Group group(long id) {
        Group group = Group.create("그룹" + id, "group" + id, "", mock(User.class), NOW);
        ReflectionTestUtils.setField(group, "id", id);
        return group;
    }

    /**
     * saveAndFlush()가 JPA의 신규 식별자를 채워 반환하는 동작을 흉내냅니다.
     * 실제 영속화는 하지 않고 저장된 엔티티를 그대로 돌려줍니다.
     * 서로 다른 그룹에 고유 그룹 시즌 ID가 할당되도록 구성합니다.
     */
    private void stubSavedGroupSeasons() {
        AtomicInteger sequence = new AtomicInteger(1000);
        when(groupSeasonRepository.saveAndFlush(any(GroupSeason.class))).thenAnswer(invocation -> {
            GroupSeason groupSeason = invocation.getArgument(0);
            ReflectionTestUtils.setField(groupSeason, "id", (long) sequence.incrementAndGet());
            return groupSeason;
        });
    }

    /**
     * 테스트에서만 시간을 변경할 수 있는 UTC Clock 구현입니다.
     * Thread.sleep 없이 만료 경계를 재현할 수 있습니다.
     * 각 테스트는 서로 독립적인 Clock 인스턴스를 사용합니다.
     */
    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        /**
         * 다음 Clock 읽기에서 반환할 테스트 시간을 지정합니다.
         * DB 잠금이 해제된 뒤 경계에 도달하는 상황을 테스트합니다.
         * 실제 시스템 시간을 변경하지 않습니다.
         */
        private void set(Instant next) {
            this.value = next;
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
