package com.team.sharedcreature.season.application;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;
import com.team.sharedcreature.season.domain.SeasonMemberRankSnapshot;
import com.team.sharedcreature.season.domain.SeasonRankingCalculator;
import com.team.sharedcreature.season.infrastructure.SeasonMemberContributionRepository;
import com.team.sharedcreature.season.infrastructure.SeasonMemberRankSnapshotRepository;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * 개인 종료 랭킹의 그룹별 정렬과 100건 배치 저장 규칙을 검증합니다.
 * 탈퇴자와 그룹 이동 이력, 닉네임 고정, 중복 실행을 검사합니다.
 * 실제 DB Commit·잠금 경합 검증은 다음 MySQL 통합 테스트에서 수행합니다.
 */
@ExtendWith(MockitoExtension.class)
class SeasonMemberSnapshotServiceTest {

    private static final Long SEASON_ID = 91L;
    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T1 = CREATED.plusSeconds(60);
    private static final Instant T2 = CREATED.plusSeconds(120);
    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    @Mock SeasonRepository seasonRepository;
    @Mock SeasonMemberContributionRepository contributionRepository;
    @Mock SeasonMemberRankSnapshotRepository snapshotRepository;
    @Mock UserProfileRepository profileRepository;

    private SeasonMemberSnapshotService service;

    /**
     * 모든 테스트에 동일한 UTC 시각과 Repository Mock을 연결합니다.
     * 현재 시즌과 관련된 실제 도메인 객체는 테스트별로 새로 생성합니다.
     * 외부 MySQL 연결 없이 순수 배치 처리 규칙만 검증합니다.
     */
    @BeforeEach
    void setUp() {
        service = new SeasonMemberSnapshotService(
                seasonRepository,
                contributionRepository,
                snapshotRepository,
                profileRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    /**
     * 개인 순위는 그룹 시즌마다 1번부터 계산되어야 합니다.
     * 같은 사용자가 서로 다른 그룹 시즌에 남긴 기여도도 보존합니다.
     * 탈퇴 상태와 닉네임, 점수 도달 시각을 Snapshot에 복사합니다.
     */
    @Test
    void ranksArePerGroupSeasonAndKeepMovedMembers() {
        Season season = closingSeason("ACTIVE");
        GroupSeason firstGroup = groupSeason(season, 10L);
        GroupSeason nextGroup = groupSeason(season, 20L);
        User movedUser = user(1L);
        User secondUser = user(2L);

        // 이동 전 그룹의 탈퇴자 기록도 순위에서 제외하지 않습니다.
        SeasonMemberContribution first = contribution(firstGroup, movedUser, 100L, T1, "LEFT");
        SeasonMemberContribution second = contribution(firstGroup, secondUser, 100L, T2, "ACTIVE");
        SeasonMemberContribution moved = contribution(nextGroup, movedUser, 10L, T2, "ACTIVE");
        arrange(season, List.of(moved, second, first));
        stubProfiles(List.of(profile(movedUser, "이동회원"), profile(secondUser, "기여회원")));

        SeasonMemberSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isEqualTo(3);
        assertThat(progress.inserted()).isEqualTo(3);
        assertThat(progress.remaining()).isZero();

        ArgumentCaptor<SeasonMemberRankSnapshot> captor =
                ArgumentCaptor.forClass(SeasonMemberRankSnapshot.class);
        verify(snapshotRepository, times(3)).save(captor.capture());
        List<SeasonMemberRankSnapshot> snapshots = captor.getAllValues();

        // 먼저 그룹 ID 10의 1위·2위, 이후 그룹 ID 20의 1위를 저장합니다.
        assertThat(snapshots).extracting(s -> s.getGroup().getId())
                .containsExactly(10L, 10L, 20L);
        assertThat(snapshots).extracting(SeasonMemberRankSnapshot::getRankNumber)
                .containsExactly(1, 2, 1);
        assertThat(snapshots).extracting(s -> s.getUser().getId())
                .containsExactly(1L, 2L, 1L);
        assertThat(snapshots).extracting(SeasonMemberRankSnapshot::getParticipantStatus)
                .containsExactly("LEFT", "ACTIVE", "ACTIVE");
        assertThat(snapshots).extracting(SeasonMemberRankSnapshot::getNicknameSnapshot)
                .containsExactly("이동회원", "기여회원", "이동회원");
        assertThat(snapshots).extracting(SeasonMemberRankSnapshot::getCreatedAt)
                .containsExactly(NOW, NOW, NOW);
    }

    /**
     * 이전 배치에서 저장한 Snapshot은 재생성하지 않습니다.
     * 이후 사용자 닉네임이나 참가 상태가 바뀌어도 기존 값이 보존됩니다.
     * 누락된 Snapshot이 없으면 프로필 Repository를 다시 조회하지 않습니다.
     */
    @Test
    void existingSnapshotIsNotRewrittenAfterProfileOrStatusChanges() {
        Season season = closingSeason("ACTIVE");
        GroupSeason groupSeason = groupSeason(season, 10L);
        User user = user(1L);
        SeasonMemberContribution source = contribution(groupSeason, user, 100L, T1, "ACTIVE");
        UserProfile oldProfile = profile(user, "기존닉네임");
        SeasonMemberRankSnapshot existing = SeasonMemberRankSnapshot.capture(
                season, groupSeason, source, oldProfile,
                new SeasonRankingCalculator.MemberRank(1, 1L, 100L, T1), CREATED
        );

        // 기존 Snapshot만 완료된 상태에서 원본 참가 상태를 변경합니다.
        ReflectionTestUtils.setField(source, "participantStatus", "LEFT");
        arrange(season, List.of(source));
        when(snapshotRepository.findAllBySeasonId(SEASON_ID)).thenReturn(List.of(existing));

        SeasonMemberSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.inserted()).isZero();
        assertThat(progress.remaining()).isZero();
        assertThat(existing.getParticipantStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getNicknameSnapshot()).isEqualTo("기존닉네임");
        assertThat(existing.getCreatedAt()).isEqualTo(CREATED);
        verifyNoInteractions(profileRepository);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * ACTIVE가 되지 못하고 만료된 SCHEDULED 시즌은 개인 랭킹이 없습니다.
     * 원본 기여도나 프로필을 조회하지 않습니다.
     * 이전에 확정한 시즌 출처 판정과 같은 정책을 적용합니다.
     */
    @Test
    void expiredScheduledSeasonSkipsSnapshots() {
        Season season = closingSeason("SCHEDULED");
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));

        SeasonMemberSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isZero();
        assertThat(progress.inserted()).isZero();
        assertThat(progress.remaining()).isZero();
        verifyNoInteractions(contributionRepository, snapshotRepository, profileRepository);
    }

    /**
     * 새로 기록할 사용자의 프로필이 없으면 전체 배치를 실패시킵니다.
     * 닉네임을 임의로 만들거나 빈 문자열로 저장하지 않습니다.
     * 프로필 누락을 발견한 시점에는 Snapshot 저장을 하지 않습니다.
     */
    @Test
    void missingProfileRejectsWholeBatch() {
        Season season = closingSeason("ACTIVE");
        GroupSeason groupSeason = groupSeason(season, 10L);
        SeasonMemberContribution source = contribution(groupSeason, user(1L), 100L, T1, "ACTIVE");
        arrange(season, List.of(source));

        assertThatThrownBy(() -> service.writeNextBatch(SEASON_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로필");
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 이미 저장된 Snapshot이 실제 계산 순위와 다르면 종료를 중단합니다.
     * 잘못 저장된 순위를 덮어쓰지 않습니다.
     * 새로운 사용자 프로필도 조회하기 전에 오류를 발견해야 합니다.
     */
    @Test
    void mismatchedExistingRankingIsRejected() {
        Season season = closingSeason("ACTIVE");
        GroupSeason groupSeason = groupSeason(season, 10L);
        User user = user(1L);
        SeasonMemberContribution source = contribution(groupSeason, user, 100L, T1, "ACTIVE");
        SeasonMemberRankSnapshot wrong = SeasonMemberRankSnapshot.capture(
                season, groupSeason, source, profile(user, "기존회원"),
                new SeasonRankingCalculator.MemberRank(2, 1L, 100L, T1), CREATED
        );
        arrange(season, List.of(source));
        when(snapshotRepository.findAllBySeasonId(SEASON_ID)).thenReturn(List.of(wrong));

        assertThatThrownBy(() -> service.writeNextBatch(SEASON_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일치하지");
        verifyNoInteractions(profileRepository);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 원본 개인 기여도가 101개여도 최대 100개만 저장합니다.
     * 여러 그룹 시즌에 걸친 연속 배치에서도 남은 건수를 반환합니다.
     * 실제 입력 그룹은 각자 1명으로 만들어 그룹 정원 제약을 해치지 않습니다.
     */
    @Test
    void oneBatchWritesAtMostOneHundredMembers() {
        Season season = closingSeason("ACTIVE");
        List<SeasonMemberContribution> contributions = new ArrayList<>();
        List<UserProfile> profiles = new ArrayList<>();

        // 서로 다른 101개 그룹 시즌에 한 명씩 배치합니다.
        for (long index = 1; index <= 101; index++) {
            GroupSeason groupSeason = groupSeason(season, index);
            User user = user(index);
            contributions.add(contribution(groupSeason, user, index, T1, "ACTIVE"));
            profiles.add(profile(user, "사용자" + index));
        }
        arrange(season, contributions);
        stubProfiles(profiles);

        SeasonMemberSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isEqualTo(101);
        assertThat(progress.inserted()).isEqualTo(100);
        assertThat(progress.remaining()).isEqualTo(1);
        verify(snapshotRepository, times(100)).save(any(SeasonMemberRankSnapshot.class));
    }

    /**
     * 도달 시각이 NULL인 개인 기여도는 잘못된 데이터입니다.
     * 점수 0도 명시된 도달 시각이 있어야 합니다.
     * 없는 시각을 임의로 대체해 랭킹을 만들지 않습니다.
     */
    @Test
    void nullMemberReachedAtIsRejected() {
        Season season = closingSeason("ACTIVE");
        GroupSeason groupSeason = groupSeason(season, 10L);
        arrange(season, List.of(contribution(groupSeason, user(1L), 0L, null, "ACTIVE")));

        assertThatThrownBy(() -> service.writeNextBatch(SEASON_ID))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(profileRepository);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 기여도 행이 없으면 개인 랭킹 Snapshot도 없습니다.
     * 이 상황은 정상적인 빈 결과이며 완료로 간주합니다.
     * 불필요한 사용자 프로필 조회나 저장이 없어야 합니다.
     */
    @Test
    void noContributionsReturnsCompletedProgress() {
        Season season = closingSeason("ACTIVE");
        arrange(season, List.of());

        SeasonMemberSnapshotService.Progress progress = service.writeNextBatch(SEASON_ID);

        assertThat(progress.total()).isZero();
        assertThat(progress.inserted()).isZero();
        assertThat(progress.remaining()).isZero();
        verifyNoInteractions(profileRepository);
        verify(snapshotRepository, never()).save(any());
    }

    /**
     * 종료 대상 시즌과 원본 기여도 조회 결과를 Mock에 설정합니다.
     * 저장되어 있던 Snapshot은 개별 테스트에서 필요할 때만 설정합니다.
     * 이 Fixture는 JPA 트랜잭션을 시작하지 않습니다.
     */
    private void arrange(Season season, List<SeasonMemberContribution> contributions) {
        when(seasonRepository.findForUpdateById(SEASON_ID)).thenReturn(Optional.of(season));
        when(contributionRepository.findAllForSeasonSnapshot(SEASON_ID)).thenReturn(contributions);
    }

    /**
     * 배치에서 요청한 사용자 ID에 해당하는 프로필만 반환합니다.
     * 동일 사용자가 여러 그룹 시즌에 있을 때도 하나의 프로필을 제공합니다.
     * 실제 Spring Data 파생 쿼리의 반환 형태를 모의 처리합니다.
     */
    private void stubProfiles(List<UserProfile> profiles) {
        when(profileRepository.findAllByUserIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    return profiles.stream()
                            .filter(profile -> ids.contains(profile.getUser().getId()))
                            .toList();
                });
    }

    /**
     * CLOSING 시즌을 protected JPA 생성자에 대한 리플렉션으로 만듭니다.
     * 다른 패키지에서 new Season()을 호출하지 않습니다.
     * 종료 이전 상태는 테스트별로 ACTIVE 또는 SCHEDULED를 지정합니다.
     */
    private Season closingSeason(String originalStatus) {
        Season season = BeanUtils.instantiateClass(Season.class);
        ReflectionTestUtils.setField(season, "id", SEASON_ID);
        ReflectionTestUtils.setField(season, "status", "CLOSING");
        ReflectionTestUtils.setField(season, "closingFromStatus", originalStatus);
        return season;
    }

    /**
     * 시즌에 참여한 독립 그룹 엔티티를 생성합니다.
     * 실제 도메인 팩토리로 생성한 다음 테스트 ID만 지정합니다.
     * 그룹 시즌 ID는 그룹 ID에 1000을 더해 고정합니다.
     */
    private GroupSeason groupSeason(Season season, long groupId) {
        Group group = Group.create("그룹" + groupId, "group" + groupId, "", user(999L), CREATED);
        ReflectionTestUtils.setField(group, "id", groupId);
        GroupSeason groupSeason = GroupSeason.start(group, season, CREATED);
        ReflectionTestUtils.setField(groupSeason, "id", 1000L + groupId);
        return groupSeason;
    }

    /**
     * 사용자의 ID를 테스트 데이터에 설정합니다.
     * User.register()로 실제 도메인의 기본 상태를 유지합니다.
     * 다른 스레드나 DB의 AUTO_INCREMENT에 의존하지 않습니다.
     */
    private User user(long userId) {
        User user = User.register("USER", CREATED);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    /**
     * 개인 기여도 엔티티의 JPA 복원 상태를 재현합니다.
     * 현재 엔티티에 공개 생성 팩토리가 없으므로 테스트에서만 Reflection을 사용합니다.
     * 점수와 참가 상태는 지정한 값 그대로 입력합니다.
     */
    private SeasonMemberContribution contribution(
            GroupSeason groupSeason, User user, Long score, Instant reachedAt, String status
    ) {
        SeasonMemberContribution contribution = BeanUtils.instantiateClass(SeasonMemberContribution.class);
        ReflectionTestUtils.setField(contribution, "groupSeason", groupSeason);
        ReflectionTestUtils.setField(contribution, "user", user);
        ReflectionTestUtils.setField(contribution, "contributionScore", score);
        ReflectionTestUtils.setField(contribution, "scoreReachedAt", reachedAt);
        ReflectionTestUtils.setField(contribution, "participantStatus", status);
        return contribution;
    }

    /**
     * 사용자 ID에 연결된 표시 닉네임 프로필을 생성합니다.
     * 실제 도메인 팩토리로 생성하여 ID 연관관계를 검증합니다.
     * 생성 후 닉네임이 수정되더라도 Snapshot 값은 덮어쓰지 않습니다.
     */
    private UserProfile profile(User user, String nickname) {
        return UserProfile.create(user, nickname, nickname.toLowerCase(), CREATED);
    }
}
