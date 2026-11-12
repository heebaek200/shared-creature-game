package com.team.sharedcreature.season.domain;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupMembership;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 개인 기여도 행의 참가 상태가 멤버십 상태를 따라가는지 검증합니다.
 * 이미 획득한 점수와 점수 도달 시각은 어떤 상태 변경에도 유지돼야 합니다.
 * 새로운 멤버십을 발급받아 재가입한 경우 최신 참조도 교체합니다.
 */
class SeasonMemberContributionStateTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant CHANGE = START.plusSeconds(500);

    /**
     * 자진 탈퇴하면 참가 상태만 LEFT로 변경해야 합니다.
     * 멤버십의 종료 상태를 그대로 사용하고 임의 문자열을 받지 않습니다.
     * 과거 기여 점수와 도달 시각은 유지돼야 합니다.
     */
    @Test
    void selfDepartureMarksLeftWithoutChangingScore() {
        Fixture f = fixture();
        Instant reachedAt = f.contribution().getScoreReachedAt();
        f.membership().leaveBySelf(f.user(), CHANGE);

        // 회원 탈퇴 후 상태만 바뀌었는지 검사합니다.
        f.contribution().synchronizeMembership(f.membership(), CHANGE);
        assertEquals("LEFT", f.contribution().getParticipantStatus());
        assertEquals(50L, f.contribution().getContributionScore());
        assertEquals(reachedAt, f.contribution().getScoreReachedAt());
    }

    /**
     * 비활동 강퇴 상태를 REMOVED로 보존해야 합니다.
     * 회원의 기여 점수를 강제로 삭제하거나 0으로 만들지 않습니다.
     * 메서드가 멤버십 상태를 직접 읽어 참가 상태를 결정해야 합니다.
     */
    @Test
    void removedMemberIsMarkedRemoved() {
        Fixture f = fixture();
        User actor = User.register("USER", START);
        ReflectionTestUtils.setField(actor, "id", 99L);
        f.membership().removeForInactivity(actor, CHANGE);

        // 강퇴도 동일한 상태 동기화 메서드를 사용합니다.
        f.contribution().synchronizeMembership(f.membership(), CHANGE);
        assertEquals("REMOVED", f.contribution().getParticipantStatus());
        assertEquals(50L, f.contribution().getContributionScore());
    }

    /**
     * 같은 그룹에 재가입하면 이전 참가 상태를 ACTIVE로 되돌립니다.
     * 새로운 멤버십을 latestMembership으로 교체해야 이후 이력과 일치합니다.
     * 기존 점수와 도달 시각은 그대로 유지돼야 합니다.
     */
    @Test
    void rejoiningSameGroupRestoresActiveState() {
        Fixture f = fixture();
        Instant reachedAt = f.contribution().getScoreReachedAt();
        f.membership().leaveBySelf(f.user(), CHANGE);
        f.contribution().synchronizeMembership(f.membership(), CHANGE);
        GroupMembership newMembership = GroupMembership.member(f.group(), f.user(), CHANGE.plusSeconds(60));
        ReflectionTestUtils.setField(newMembership, "id", 51L);

        // 새 멤버십을 통해 같은 그룹 시즌에 복귀합니다.
        f.contribution().synchronizeMembership(newMembership, CHANGE.plusSeconds(60));
        assertEquals("ACTIVE", f.contribution().getParticipantStatus());
        assertSame(newMembership, f.contribution().getLatestMembership());
        assertEquals(50L, f.contribution().getContributionScore());
        assertEquals(reachedAt, f.contribution().getScoreReachedAt());
    }

    /**
     * 상태 전환 이전에 50점의 기여도를 가진 회원을 준비합니다.
     * 관계 비교 검증에서 사용하는 식별자를 테스트용으로 지정합니다.
     * 데이터베이스를 사용하지 않고 도메인 메서드만 실행합니다.
     */
    private Fixture fixture() {
        User user = User.register("USER", START);
        ReflectionTestUtils.setField(user, "id", 10L);
        Group group = Group.create("상태테스트", "상태테스트", "", user, START);
        ReflectionTestUtils.setField(group, "id", 20L);
        GroupMembership membership = GroupMembership.member(group, user, START);
        ReflectionTestUtils.setField(membership, "id", 50L);
        GroupSeason groupSeason = GroupSeason.start(group, mock(Season.class), START);
        ReflectionTestUtils.setField(groupSeason, "id", 30L);
        SeasonMemberContribution contribution =
                SeasonMemberContribution.zero(groupSeason, user, membership, START);

        // 기여도가 없는 초기 상태가 아니라 이미 보상받은 상태를 검사합니다.
        contribution.addContribution(50L, membership, START.plusSeconds(120));
        return new Fixture(user, group, membership, contribution);
    }

    private record Fixture(
            User user,
            Group group,
            GroupMembership membership,
            SeasonMemberContribution contribution
    ) {}
}
