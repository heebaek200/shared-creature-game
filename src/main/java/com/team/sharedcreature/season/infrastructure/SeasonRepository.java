package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Season 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findFirstByStatusOrderByStartAtDesc(String status);
}
