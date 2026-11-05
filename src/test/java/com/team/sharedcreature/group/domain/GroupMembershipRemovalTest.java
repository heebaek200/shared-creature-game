package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비활동 강퇴 시 멤버십의 역할·종료 상태를 검증합니다.
 *
 * 실제 데이터베이스 없이 Entity 메서드를 직접 실행합니다.
 * PRIMARY 강퇴 조건이 반대로 구현되는 회귀를 방지합니다.
 */
class GroupMembershipRemovalTest {

    private static final Instant NOW =
            Instant.parse("2026-11-05T04:00:00Z");

    /**
     * 일반 회원은 비활동 강퇴로 종료될 수 있어야 합니다.
     *
     * 종료 상태와 종료 사유, 실행자 기록을 확인합니다.
     * 기존 역할은 MEMBER로 보존되어야 합니다.
     */
    @Test
    void MEMBER는_강퇴할_수_있다() {
        User actor = user(1L);
        User target = user(2L);
        Group group = Group.create("그룹", "그룹", "", actor, NOW);

        // 실제 MEMBER 멤버십으로 강퇴를 실행합니다.
        GroupMembership membership =
                GroupMembership.member(group, target, NOW);

        membership.removeForInactivity(actor, NOW);

        assertThat(membership.getStatus()).isEqualTo("REMOVED");
        assertThat(membership.getRole()).isEqualTo("MEMBER");
        assertThat(membership.getExitReason()).isEqualTo("INACTIVE");
        assertThat(membership.getEndedByUser()).isSameAs(actor);
        assertThat(membership.getEndedAt()).isEqualTo(NOW);
    }

    /**
     * PRIMARY 멤버십은 강퇴할 수 없어야 합니다.
     *
     * 다른 관리자가 요청한 상황도 도메인에서 거부합니다.
     * 실패 시 원래 멤버십 상태를 유지해야 합니다.
     */
    @Test
    void PRIMARY는_강퇴할_수_없다() {
        User actor = user(1L);
        User target = user(2L);
        Group group = Group.create("그룹", "그룹", "", target, NOW);

        // PRIMARY에 대한 종료 요청은 예외로 처리해야 합니다.
        GroupMembership membership =
                GroupMembership.primary(group, target, NOW);

        assertThatThrownBy(() ->
                membership.removeForInactivity(actor, NOW)
        ).isInstanceOf(IllegalStateException.class);

        assertThat(membership.getStatus()).isEqualTo("ACTIVE");
        assertThat(membership.getEndedAt()).isNull();
    }

    /**
     * 테스트에 사용할 회원을 생성하고 ID를 부여합니다.
     *
     * JPA 저장소를 실행하지 않으므로 PK를 직접 설정합니다.
     * 서로 다른 회원을 구별할 수 있는 Entity를 반환합니다.
     */
    private User user(Long id) {
        // 테스트에서만 Reflection으로 식별자를 주입합니다.
        User user = User.register("USER", NOW);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}