package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.Season;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Season 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findFirstByStatusOrderByStartAtDesc(String status);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT season
              FROM Season season
             WHERE season.status = :status
             ORDER BY season.startAt DESC
            """)
    List<Season> findForShareByStatus(@Param("status") String status, Pageable pageable);
}
