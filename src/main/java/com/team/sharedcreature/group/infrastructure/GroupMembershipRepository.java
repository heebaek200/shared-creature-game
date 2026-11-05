package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.domain.GroupMembership;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/**
 * GroupMembership 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, Long> {
    boolean existsByUserIdAndStatus(Long userId, String status);

    Optional<GroupMembership> findByUserIdAndStatus(Long userId, String status);

    @Query("""
            SELECT membership
              FROM GroupMembership membership
              JOIN FETCH membership.group
              JOIN FETCH membership.user
             WHERE membership.user.id = :userId
               AND membership.status = 'ACTIVE'
            """)
    Optional<GroupMembership> findActiveWithGroupByUserId(@Param("userId") Long userId);

    /**
     * 지정한 그룹의 활성 구성원과 프로필, 마지막 활동 시각을 페이지 조회합니다.
     *
     * 멤버십과 사용자 프로필을 결합해 구성원별 추가 SELECT를 방지합니다.
     * 탈퇴·강퇴된 멤버십은 제외하고 가입 시각 및 멤버십 ID로 순서를 고정합니다.
     *
     * @param groupId 조회할 그룹 ID
     * @param pageable 페이지 번호와 크기
     * @return 활성 구성원 DTO 페이지
     */
    @Query(
            value = """
                SELECT new com.team.sharedcreature.group.api.GroupMemberResponse(
                    account.id,
                    profile.nickname,
                    membership.id,
                    membership.role,
                    membership.status,
                    membership.joinedAt,
                    account.lastActiveAt
                )
                  FROM GroupMembership membership
                  JOIN membership.user account
                  JOIN UserProfile profile ON profile.user.id = account.id
                 WHERE membership.group.id = :groupId
                   AND membership.status = 'ACTIVE'
                 ORDER BY membership.joinedAt ASC, membership.id ASC
                """,
            countQuery = """
                SELECT COUNT(membership.id)
                  FROM GroupMembership membership
                 WHERE membership.group.id = :groupId
                   AND membership.status = 'ACTIVE'
                """
    )
    Page<GroupMemberResponse> findActiveMemberResponsesByGroupId(
            @Param("groupId") Long groupId,
            Pageable pageable
    );

    /**
     * 사용자의 현재 활성 그룹 ID만을 조회
     *  - 잠금 순서를 결정하기 위한 사전 조회용
     *  - 잠금 획득 후 반드시 다시 검증해야 함
     */
    @Query("""
        SELECT
            membership.group.id
        FROM
            GroupMembership membership
        WHERE
            membership.user.id = :userId
        AND membership.status = 'ACTIVE'
""")
    Optional<Long> findActiveGroupIdByUserId(@Param("userId") Long userId);

    /**
     * 지정한 그룹에 소속된 활성 회원의 멤버십 ID를 조회
     * - 잠금 순서를 위한 사전 조회용
     * - 실제 역할과 소속 상태는 행 잠금 후 다시 검사해야 함
     */
    @Query("""
            SELECT
                membership.id
            FROM
                GroupMembership membership
            WHERE
                membership.group.id = :groupId
            AND membership.user.id  = :userId
            AND membership.status   = 'ACTIVE'
""")
    Optional<Long> findActiveMembershipId(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId
    );

    /**
     * 멤버십 ID를 기준으로 비관적 쓰기 잠금을 획득, 대상 멤버십의 최신 역할과 활성 상태를 검사.
     * - 멤버십 ID를 오름차순으로 정렬한 뒤 호출해야 함
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT
                membership
            FROM
                GroupMembership membership
            WHERE
                membership.id = :membershipId
""")
    Optional<GroupMembership> findByIdForUpdate(
            @Param("membershipId") Long membershipId
    );
}
