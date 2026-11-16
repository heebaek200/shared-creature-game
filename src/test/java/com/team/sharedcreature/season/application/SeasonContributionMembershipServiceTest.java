package com.team.sharedcreature.season.application;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * 멤버십 변경 후 현재 시즌의 개인 기여도만 동기화되는지 확인합니다.
 * 시즌·그룹 시즌이 없으면 이력 생성을 하지 않아야 합니다.
 * DB 제약과 실제 잠금 순서는 별도 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonContributionMembershipServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    @Mock GroupSeasonRepository groupSeasonRepository;
    @Mock SeasonMemberContributionRepository contributionRepository;
    @Mock Season season;
    @Mock Group group;
    @Mock User user;
    @Mock GroupMembership membership;
    @Mock GroupSeason groupSeason;
    @Mock SeasonMemberContribution contribution;

    /**
     * ACTIVE 시즌과 개인 기여도가 있으면 기존 행만 동기화합니다.
     * 같은 그룹 시즌의 기여도 한 건을 정확히 조회하는지 확인합니다.
     * 조회된 기여도에 멤버십과 변경 시각을 전달합니다.
     */
    @Test
    void updatesExistingContribution() {
        when(season.isActiveAt(NOW)).thenReturn(true);
        when(season.getId()).thenReturn(1L);
        when(membership.getGroup()).thenReturn(group);
        when(membership.getUser()).thenReturn(user);
        when(group.getId()).thenReturn(10L);
        when(user.getId()).thenReturn(20L);
        when(groupSeason.getId()).thenReturn(30L);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 1L))
                .thenReturn(Optional.of(groupSeason));
        when(contributionRepository.findByGroupSeasonIdAndUserId(30L, 20L))
                .thenReturn(Optional.of(contribution));

        // 실제 행이 있으면 상태 동기화 메서드에 위임합니다.
        service().synchronizeIfPresent(Optional.of(season), membership, NOW);
        verify(contribution).synchronizeMembership(membership, NOW);
    }

    /**
     * 현재 ACTIVE 시즌이 없으면 지난 기여도를 수정하지 않습니다.
     * 시즌 생성 전 또는 종료 복구 중의 가입·탈퇴를 허용합니다.
     * 기여도 Repository에는 접근하지 않아야 합니다.
     */
    @Test
    void skipsWhenNoActiveSeason() {
        service().synchronizeIfPresent(Optional.empty(), membership, NOW);
        verifyNoInteractions(groupSeasonRepository, contributionRepository);
    }

    /**
     * 조회한 시즌의 기간이 이미 끝났다면 상태 동기화를 하지 않습니다.
     * 종료 경계 이후에 발생한 가입·탈퇴를 지난 시즌에 소급 반영하지 않습니다.
     * 그룹 시즌 또는 개인 기여도 조회도 하지 않습니다.
     */
    @Test
    void skipsWhenSeasonExpired() {
        when(season.isActiveAt(NOW)).thenReturn(false);
        service().synchronizeIfPresent(Optional.of(season), membership, NOW);
        verifyNoInteractions(groupSeasonRepository, contributionRepository);
    }

    /**
     * 현재 그룹의 시즌 참여 기록이 없다면 기여도도 생성하지 않습니다.
     * 중간 초기화 기간 또는 구성원만 있는 그룹 상황을 허용합니다.
     * 개인 기여도 Repository의 조회를 생략합니다.
     */
    @Test
    void skipsWhenGroupSeasonMissing() {
        when(season.isActiveAt(NOW)).thenReturn(true);
        when(season.getId()).thenReturn(1L);
        when(membership.getGroup()).thenReturn(group);
        when(membership.getUser()).thenReturn(user);
        when(group.getId()).thenReturn(10L);
        when(user.getId()).thenReturn(20L);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 1L))
                .thenReturn(Optional.empty());

        // 그룹 시즌이 없으면 개인 기여도는 조회하지 않습니다.
        service().synchronizeIfPresent(Optional.of(season), membership, NOW);
        verifyNoInteractions(contributionRepository);
    }

    /**
     * 현재 그룹 시즌은 있지만 기여도가 없다면 새로운 행을 만들지 않습니다.
     * 아직 점수를 획득하지 않은 사용자는 기여도 행이 없는 것이 정상입니다.
     * 배타 잠금은 가입·탈퇴 호출자가 획득한 사용자 행으로 직렬화합니다.
     */
    @Test
    void skipsWhenContributionMissing() {
        when(season.isActiveAt(NOW)).thenReturn(true);
        when(season.getId()).thenReturn(1L);
        when(membership.getGroup()).thenReturn(group);
        when(membership.getUser()).thenReturn(user);
        when(group.getId()).thenReturn(10L);
        when(user.getId()).thenReturn(20L);
        when(groupSeason.getId()).thenReturn(30L);
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(10L, 1L))
                .thenReturn(Optional.of(groupSeason));
        when(contributionRepository.findByGroupSeasonIdAndUserId(30L, 20L))
                .thenReturn(Optional.empty());

        // 기존 점수 행이 없을 때는 새 기여도 행을 생성하지 않습니다.
        service().synchronizeIfPresent(Optional.of(season), membership, NOW);
        verify(contributionRepository, never()).save(any());
    }

    /**
     * 테스트용 Service를 실제 생성자 계약대로 생성합니다.
     * Mock Repository를 주입하며 Spring 트랜잭션 프록시는 생성하지 않습니다.
     * 트랜잭션 전파 속성은 통합 테스트에서 확인해야 합니다.
     */
    private SeasonContributionMembershipService service() {
        return new SeasonContributionMembershipService(groupSeasonRepository, contributionRepository);
    }
}
