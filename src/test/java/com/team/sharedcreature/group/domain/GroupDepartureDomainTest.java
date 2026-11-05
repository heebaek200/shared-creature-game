package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자진 탈퇴 시 사용되는 JPA Entity의 상태 전이 규칙을 단독 검증합니다.
 *
 * DB나 Repository 없이 팩토리로 생성한 실제 엔티티를 테스트합니다.
 * 상태값 오타, 중복 종료, 잘못된 실행자의 탈퇴를 빠르게 감지합니다.
 */
class GroupDepartureDomainTest {
    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");

    /**
     * 다른 회원은 본인의 멤버십이 아닌 기록을 종료할 수 없습니다.
     *
     * 실행자 ID를 비교해 탈퇴 요청의 주체가 일치하는지 검사합니다.
     * 실패한 경우 기존 활성 멤버십 상태를 유지합니다.
     */
    @Test
    void 다른_회원의_멤버십은_자진_탈퇴할_수_없다() {
        User member = user(1L);
        User stranger = user(2L);
        Group group = Group.create("그룹", "그룹", "", stranger, CREATED);
        GroupMembership membership = GroupMembership.member(group, member, CREATED);

        // ID가 다른 사용자를 실행자로 넘기면 즉시 차단합니다.
        assertThatThrownBy(() -> membership.leaveBySelf(stranger, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(membership.getStatus()).isEqualTo("ACTIVE");
        assertThat(membership.getEndedAt()).isNull();
    }

    /**
     * 이미 탈퇴한 멤버십은 두 번 종료할 수 없습니다.
     *
     * 종료 기록과 종료 시각을 한 번 확정하면 유지해야 합니다.
     * 중복 호출 시 상태를 다시 수정하지 않아야 합니다.
     */
    @Test
    void 동일한_멤버십의_중복_탈퇴는_거부한다() {
        User member = user(1L);
        Group group = Group.create("그룹", "그룹", "", member, CREATED);
        GroupMembership membership = GroupMembership.member(group, member, CREATED);
        membership.leaveBySelf(member, NOW);

        // 두 번째 호출이 기존 종료 시각을 변경하는지 검증합니다.
        assertThatThrownBy(() -> membership.leaveBySelf(member, NOW.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(membership.getEndedAt()).isEqualTo(NOW);
        assertThat(membership.getExitReason()).isEqualTo("SELF");
    }

    /**
     * 그룹 종료 시 초대 링크를 INVALIDATED로 표시해야 합니다.
     *
     * DB 상태값 계약과 정확히 일치하는지 검사하는 회귀 테스트입니다.
     * 현재 검토 대상 커밋의 INVALIDATE 오타를 발견하도록 작성했습니다.
     */
    @Test
    void 그룹_종료는_초대_링크를_INVALIDATED로_변경한다() {
        User primary = user(1L);
        Group group = Group.create("그룹", "그룹", "", primary, CREATED);
        GroupInviteLink link = GroupInviteLink.issue(
                group, "a".repeat(64), primary, CREATED);

        // 외부 API와 DB가 사용하는 정확한 종료 상태를 검사합니다.
        link.invalidate(NOW);
        assertThat(link.getStatus()).isEqualTo("INVALIDATED");
        assertThat(link.getInvalidatedAt()).isEqualTo(NOW);
    }

    /**
     * 이미 종료된 초대 링크는 다시 종료하더라도 시각이 변경되지 않습니다.
     *
     * 한 번 폐기한 초대 토큰은 재발급 대상이 아닙니다.
     * 반복 무효화의 멱등성을 확인합니다.
     */
    @Test
    void 이미_종료된_초대_링크는_기존_종료시각을_유지한다() {
        User primary = user(1L);
        Group group = Group.create("그룹", "그룹", "", primary, CREATED);
        GroupInviteLink link = GroupInviteLink.issue(
                group, "a".repeat(64), primary, CREATED);
        link.invalidate(NOW);

        // 두 번째 요청은 기존 이력의 시각을 유지해야 합니다.
        link.invalidate(NOW.plusSeconds(100));
        assertThat(link.getInvalidatedAt()).isEqualTo(NOW);
    }

    /**
     * 그룹 가입 제한은 현재 시각으로부터 정확히 24시간입니다.
     *
     * 기존 제한 행을 다시 사용할 때 생성 시각은 보존되어야 합니다.
     * 새로운 멤버십에 대한 제한이 최신 시각으로 갱신되는지 검사합니다.
     */
    @Test
    void 가입제한_갱신은_기존_생성시각을_보존한다() {
        User user = user(1L);
        Group group = Group.create("그룹", "그룹", "", user, CREATED);
        GroupMembership first = GroupMembership.primary(group, user, CREATED);
        GroupMembership second = GroupMembership.primary(group, user, NOW);
        GroupJoinRestriction restriction = GroupJoinRestriction.forDeparture(
                user, first, "SELF", CREATED);

        // 기존 행의 생성일과 새 제한 기간을 함께 확인합니다.
        restriction.renew(second, "SELF", NOW);
        assertThat(restriction.getCreatedAt()).isEqualTo(CREATED);
        assertThat(restriction.getSourceMembership()).isSameAs(second);
        assertThat(restriction.getRestrictedFrom()).isEqualTo(NOW);
        assertThat(restriction.getRestrictedUntil()).isEqualTo(NOW.plusSeconds(86_400));
    }

    /**
     * 테스트에서 사용할 ID가 고정된 실제 사용자 객체를 생성합니다.
     *
     * 영속성 계층이 없는 단위 테스트이므로 DB에서 생성하는 ID만 주입합니다.
     * 이후 테스트 로직은 실제 User Entity의 메서드를 호출합니다.
     */
    private User user(long id) {
        // ID 외 모든 값은 도메인 팩토리에서 초기화합니다.
        User user = User.register("USER", CREATED);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
