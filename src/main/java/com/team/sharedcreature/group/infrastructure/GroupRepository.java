package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.Group;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/**
 * Group 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface GroupRepository extends JpaRepository<Group, Long> {
    boolean existsByNormalizedName(String normalizedName);

    Optional<Group> findByNormalizedName(String normalizedName);

    /** 공개 활성 그룹을 정규화 이름의 포함 검색으로 조회합니다. */
    @Query("""
            SELECT group
              FROM Group group
             WHERE group.status = 'ACTIVE'
               AND group.visibility = 'PUBLIC'
               AND (:query = '' OR group.normalizedName LIKE CONCAT('%', :query, '%') ESCAPE '\\')
            """)
    Page<Group> findPublicGroups(@Param("query") String query, Pageable pageable);

    /** 가입 시 정원 변경을 직렬화하도록 그룹을 비관적 쓰기 잠금으로 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT group FROM Group group WHERE group.id = :groupId")
    Optional<Group> findByIdForUpdate(@Param("groupId") Long groupId);

    /** 정원이 남은 활성 공개 그룹의 카운터를 조건부로 한 명 증가시킵니다. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE game_groups
               SET current_member_count = current_member_count + 1
                 , updated_at = :now
                 , version = version + 1
             WHERE id = :groupId
               AND status = 'ACTIVE'
               AND visibility = 'PUBLIC'
               AND current_member_count < capacity
            """, nativeQuery = true)
    int incrementMemberCountIfAvailable(@Param("groupId") Long groupId, @Param("now") java.time.Instant now);
}
