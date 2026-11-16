package com.team.sharedcreature.ranking.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.*;
import com.team.sharedcreature.season.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 랭킹 조회의 정렬·페이지·권한·현재 시즌과 종료 시즌 분기를 검증합니다. */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    @Mock private SeasonQueryService seasonQueryService;
    @Mock private SeasonRepository seasonRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private SeasonMemberContributionRepository contributionRepository;
    @Mock private UserProfileRepository profileRepository;
    @Mock private SeasonMemberRankSnapshotRepository memberSnapshotRepository;
    @Mock private GroupSeasonRepository groupSeasonRepository;
    @Mock private SeasonGroupScoreRepository scoreRepository;
    @Mock private SeasonGroupRankSnapshotRepository groupSnapshotRepository;
    private RankingService service;

    @BeforeEach
    void setUp() {
        service = new RankingService(seasonQueryService, seasonRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), membershipRepository,
                contributionRepository, profileRepository, memberSnapshotRepository,
                groupSeasonRepository, scoreRepository, groupSnapshotRepository);
    }

    @Test
    void 개인_동점은_도달시각과_ID순이며_페이지밖_현재순위를_반환한다() {
        stubCurrentMember(List.of(
                contribution(4, 20, NOW, "ACTIVE"),
                contribution(3, 30, NOW, "REMOVED"),
                contribution(2, 30, NOW, "LEFT"),
                contribution(1, 30, NOW.minusSeconds(1), "ACTIVE")));

        var first = service.getMemberRanking(4L, null, 0, 2);
        var second = service.getMemberRanking(4L, null, 1, 2);

        assertThat(first.data()).extracting(row -> row.userId()).containsExactly(1L, 2L);
        assertThat(second.data()).extracting(row -> row.userId()).containsExactly(3L, 4L);
        assertThat(second.data()).extracting(row -> row.rank()).containsExactly(3, 4);
        assertThat(first.data().get(1).participantStatus()).isEqualTo("LEFT");
        assertThat(second.data().get(0).participantStatus()).isEqualTo("REMOVED");
        assertThat(first.meta().totalElements()).isEqualTo(4);
        assertThat(first.meta().totalPages()).isEqualTo(2);
        assertThat(first.meta().currentSubject().subjectId()).isEqualTo(4L);
        assertThat(first.meta().currentSubject().rank()).isEqualTo(4);
        assertThat(first.meta().currentSubject().score()).isEqualTo(20);
        // 프로필은 요청마다 일괄 조회하며 사용자별 단건 조회는 하지 않습니다.
        verify(profileRepository, times(2)).findAllByUserIdIn(anyCollection());
        verify(profileRepository, never()).findByUserId(anyLong());
    }

    @Test
    void 마지막페이지를_넘어도_현재순위와_전체개수는_유지한다() {
        stubCurrentMember(List.of(contribution(4, 10, NOW, "ACTIVE")));
        var response = service.getMemberRanking(4L, null, Integer.MAX_VALUE, 50);
        assertThat(response.data()).isEmpty();
        assertThat(response.meta().page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(response.meta().totalElements()).isEqualTo(1);
        assertThat(response.meta().totalPages()).isEqualTo(1);
        assertThat(response.meta().currentSubject().rank()).isEqualTo(1);
    }

    @Test
    void 개인기록이_없으면_빈목록과_null현재대상을_반환한다() {
        stubCurrentMember(List.of());
        var response = service.getMemberRanking(4L, null, 0, 20);
        assertThat(response.data()).isEmpty();
        assertThat(response.meta().totalPages()).isZero();
        assertThat(response.meta().currentSubject()).isNull();
        verifyNoInteractions(profileRepository);
    }

    @Test
    void 현재그룹이_조회시즌에_없으면_개인기록을_조회하지_않는다() {
        stubMembership();
        when(seasonQueryService.findCurrentSeason()).thenReturn(season("ACTIVE"));
        var response = service.getMemberRanking(4L, null, 0, 20);
        assertThat(response.data()).isEmpty();
        assertThat(response.meta().currentSubject()).isNull();
        verifyNoInteractions(contributionRepository, memberSnapshotRepository, profileRepository);
    }

    @Test
    void 그룹미가입자는_개인랭킹을_조회할수없다() {
        assertCode(() -> service.getMemberRanking(4L, null, 0, 20), ErrorCode.ROLE_FORBIDDEN);
        verifyNoInteractions(seasonQueryService, contributionRepository);
    }

    @Test
    void 비활성그룹은_개인랭킹_조회권한이_없다() {
        Group group = group(7);
        ReflectionTestUtils.setField(group, "status", "INACTIVE");
        when(membershipRepository.findActiveWithGroupByUserId(4L))
                .thenReturn(Optional.of(entity(GroupMembership.class, "group", group)));
        assertCode(() -> service.getMemberRanking(4L, null, 0, 20), ErrorCode.ROLE_FORBIDDEN);
    }

    @Test
    void 프로필이_누락돼도_랭킹대상을_임의로_제외하지_않는다() {
        stubCurrentMember(List.of(contribution(4, 10, NOW, "ACTIVE")));
        when(profileRepository.findAllByUserIdIn(anyCollection())).thenReturn(List.of());
        assertCode(() -> service.getMemberRanking(4L, null, 0, 20), ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    void 종료개인은_최신프로필과_기여도대신_확정값을_반환한다() {
        stubMembership();
        when(seasonRepository.findById(10L)).thenReturn(Optional.of(season("ENDED")));
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(7L, 10L))
                .thenReturn(Optional.of(entity(GroupSeason.class, "id", 70L)));
        var snapshot = entity(SeasonMemberRankSnapshot.class,
                "user", user(4), "rankNumber", 8, "nicknameSnapshot", "종료닉네임",
                "contributionScore", 90L, "scoreReachedAt", NOW.minusSeconds(20),
                "participantStatus", "LEFT");
        when(memberSnapshotRepository.findAllByGroupSeasonIdOrderByRankNumberAsc(70L))
                .thenReturn(List.of(snapshot));

        var first = service.getMemberRanking(4L, 10L, 0, 20);
        var second = service.getMemberRanking(4L, 10L, 0, 20);

        assertThat(first).isEqualTo(second);
        assertThat(first.data().getFirst().nickname()).isEqualTo("종료닉네임");
        assertThat(first.data().getFirst().participantStatus()).isEqualTo("LEFT");
        assertThat(first.meta().currentSubject().rank()).isEqualTo(8);
        assertThat(first.meta().currentSubject().score()).isEqualTo(90);
        verifyNoInteractions(seasonQueryService, contributionRepository, profileRepository);
        verify(memberSnapshotRepository, never()).save(any());
    }

    @Test
    void 그룹동점과_0점시각대체_및_페이지밖_현재그룹순위를_반환한다() {
        stubMembership();
        when(seasonQueryService.findCurrentSeason()).thenReturn(season("ACTIVE"));
        when(scoreRepository.findAllForGroupRanking(10L)).thenReturn(List.of(
                score(7, 0, null, NOW.minusSeconds(5)),
                score(6, 50, NOW, NOW.minusSeconds(10)),
                score(5, 50, NOW, NOW.minusSeconds(10)),
                score(4, 50, NOW.minusSeconds(1), NOW.minusSeconds(10)),
                score(8, 0, null, NOW.minusSeconds(3))));

        var first = service.getGroupRanking(4L, null, 0, 2);
        var second = service.getGroupRanking(4L, null, 1, 2);

        assertThat(first.data()).extracting(row -> row.groupId()).containsExactly(4L, 5L);
        assertThat(second.data()).extracting(row -> row.groupId()).containsExactly(6L, 7L);
        assertThat(second.data().get(1).scoreReachedAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(first.meta().totalPages()).isEqualTo(3);
        assertThat(first.meta().currentSubject().subjectId()).isEqualTo(7);
        assertThat(first.meta().currentSubject().rank()).isEqualTo(4);
        assertThat(first.meta().currentSubject().score()).isZero();
        verifyNoInteractions(contributionRepository);
    }

    @Test
    void 그룹미가입자도_그룹랭킹을_조회하며_현재대상은_null이다() {
        when(seasonQueryService.findCurrentSeason()).thenReturn(season("ACTIVE"));
        when(scoreRepository.findAllForGroupRanking(10L))
                .thenReturn(List.of(score(7, 80, NOW, NOW)));
        var response = service.getGroupRanking(4L, null, 0, 20);
        assertThat(response.data()).hasSize(1);
        assertThat(response.meta().currentSubject()).isNull();
    }

    @Test
    void 종료그룹은_저장된_그룹명과_순위를_사용한다() {
        stubMembership();
        when(seasonRepository.findById(10L)).thenReturn(Optional.of(season("ENDED")));
        Group currentGroup = group(7);
        var snapshot = entity(SeasonGroupRankSnapshot.class,
                "group", currentGroup, "rankNumber", 6, "score", 120L,
                "scoreReachedAt", NOW.minusSeconds(30), "groupNameSnapshot", "종료그룹명");
        when(groupSnapshotRepository.findAllBySeasonIdOrderByRankNumberAsc(10L))
                .thenReturn(List.of(snapshot));
        var first = service.getGroupRanking(4L, 10L, 0, 20);
        // 현재 그룹명을 바꿔도 과거 시즌의 표시명은 Snapshot에서 읽습니다.
        ReflectionTestUtils.setField(currentGroup, "name", "변경된현재이름");
        var second = service.getGroupRanking(4L, 10L, 0, 20);
        assertThat(second).isEqualTo(first);
        assertThat(second.data().getFirst().groupName()).isEqualTo("종료그룹명");
        assertThat(second.meta().currentSubject().rank()).isEqualTo(6);
        verifyNoInteractions(seasonQueryService, scoreRepository);
        verify(groupSnapshotRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "CLOSING"})
    void 미확정시즌은_조회하지_않는다(String status) {
        when(seasonRepository.findById(10L)).thenReturn(Optional.of(season(status)));
        assertCode(() -> service.getGroupRanking(4L, 10L, 0, 20), ErrorCode.INVALID_FIELD_VALUE);
        verifyNoInteractions(scoreRepository, groupSnapshotRepository);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,51"})
    void 잘못된_페이지범위를_거절한다(int page, int size) {
        when(seasonQueryService.findCurrentSeason()).thenReturn(season("ACTIVE"));
        assertCode(() -> service.getGroupRanking(4L, null, page, size), ErrorCode.INVALID_FIELD_VALUE);
    }

    @Test
    void 시즌이_없거나_ID가_잘못되면_거절한다() {
        assertCode(() -> service.getGroupRanking(4L, 0L, 0, 20), ErrorCode.INVALID_FIELD_VALUE);
        assertCode(() -> service.getGroupRanking(4L, 99L, 0, 20), ErrorCode.INVALID_FIELD_VALUE);
    }

    /** 현재 개인 랭킹 테스트에 필요한 소속·시즌·프로필만 준비합니다. */
    private void stubCurrentMember(List<SeasonMemberContribution> contributions) {
        stubMembership();
        when(seasonQueryService.findCurrentSeason()).thenReturn(season("ACTIVE"));
        when(groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(7L, 10L))
                .thenReturn(Optional.of(entity(GroupSeason.class, "id", 70L)));
        when(contributionRepository.findAllForMemberRanking(70L)).thenReturn(contributions);
        if (!contributions.isEmpty()) {
            when(profileRepository.findAllByUserIdIn(anyCollection())).thenReturn(
                    contributions.stream().map(row -> UserProfile.create(row.getUser(),
                            "사용자" + row.getUser().getId(), "user" + row.getUser().getId(), NOW)).toList());
        }
    }

    private void stubMembership() {
        when(membershipRepository.findActiveWithGroupByUserId(4L))
                .thenReturn(Optional.of(entity(GroupMembership.class, "group", group(7))));
    }

    private Season season(String status) {
        return entity(Season.class, "id", 10L, "status", status,
                "startAt", NOW.minusSeconds(3600), "endAt", NOW.plusSeconds(3600));
    }

    private Group group(long id) {
        return entity(Group.class, "id", id, "name", "그룹" + id, "status", "ACTIVE");
    }

    private User user(long id) {
        return entity(User.class, "id", id);
    }

    private SeasonMemberContribution contribution(long id, long score, Instant reachedAt, String status) {
        return entity(SeasonMemberContribution.class, "user", user(id),
                "contributionScore", score, "scoreReachedAt", reachedAt, "participantStatus", status);
    }

    private SeasonGroupScore score(long id, long score, Instant reachedAt, Instant createdAt) {
        return entity(SeasonGroupScore.class,
                "groupSeason", entity(GroupSeason.class, "group", group(id)),
                "score", score, "scoreReachedAt", reachedAt, "createdAt", createdAt);
    }

    /** DB에서 복원된 상태를 재현하며 운영 엔티티에 테스트용 생성자를 추가하지 않습니다. */
    private <T> T entity(Class<T> type, Object... fields) {
        T value = BeanUtils.instantiateClass(type);
        for (int i = 0; i < fields.length; i += 2) {
            ReflectionTestUtils.setField(value, (String) fields[i], fields[i + 1]);
        }
        return value;
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
