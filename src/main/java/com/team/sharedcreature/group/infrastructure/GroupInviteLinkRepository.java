package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.GroupInviteLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * GroupInviteLink 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupInviteLinkRepository extends JpaRepository<GroupInviteLink, Long> {
    Optional<GroupInviteLink> findByTokenHash(String tokenHash);

    Optional<GroupInviteLink> findByIdAndStatus(Long id, String status);

    /** 그룹 잠금 뒤 초대 유효성을 고정해 링크 회전과 가입을 직렬화합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT invitation
              FROM GroupInviteLink invitation
             WHERE invitation.id = :invitationId
               AND invitation.group.id = :groupId
               AND invitation.status = 'ACTIVE'
            """)
    Optional<GroupInviteLink> findActiveForUpdate(
            @Param("invitationId") Long invitationId,
            @Param("groupId") Long groupId
    );

    /**
     * 현재 그룹의 활성 초대 링크를 쓰기 잠금으로 조회함
     * - 호출하는 곳에서 그룹 행을 먼저 잠금 후 실행해야 함
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT
                gil
            FROM
                GroupInviteLink AS gil
            WHERE
                gil.group.id = :groupId
            AND gil.status = 'ACTIVE'
""")
    Optional<GroupInviteLink> findActiveByGroupIdForUpdate(@Param("groupId") Long groupId);
}
