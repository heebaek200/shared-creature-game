package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.api.GroupMemberResponse;
import com.team.sharedcreature.group.domain.GroupMembership;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
