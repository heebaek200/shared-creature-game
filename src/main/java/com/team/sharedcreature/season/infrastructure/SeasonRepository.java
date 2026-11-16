package com.team.sharedcreature.season.infrastructure;

import com.team.sharedcreature.season.domain.Season;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Season 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findFirstByStatusOrderByStartAtDesc(String status);

    /**
     * 최초 시즌 달력의 기준 시각을 읽습니다.
     * 퀘스트 누적 회전 인덱스 검증에 사용하며 시즌 생성용 배타 잠금은 잡지 않습니다.
     * 정시 실행·기동 정상화 모두 첫 시즌의 영구 기준을 공유합니다.
     */
    Optional<Season> findFirstByOrderByStartAtAsc();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT season
              FROM Season season
             WHERE season.status = :status
             ORDER BY season.startAt DESC
            """)
    List<Season> findForShareByStatus(@Param("status") String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT season FROM Season season WHERE season.id = :seasonId")
    Optional<Season> findForUpdateById(@Param("seasonId") Long seasonId);

    /**
     * 서버 중단 중 만료됐거나 중단된 종료 작업을 DB 상태 기준으로 찾아냅니다.
     * ACTIVE/SCHEDULED의 만료, CLOSING 복구, ENDED 성공 이력 누락을 포함합니다.
     * SUCCEEDED 실행 이력이 있는 ENDED 시즌은 제외해 반복 스캔을 방지합니다.
     *
     * @param now 주입된 Clock에서 읽은 조회 시각
     * @return 종료 예정 시각과 ID 순으로 정렬된 시즌 ID 목록
     */
    @Query("""
            SELECT season.id
              FROM Season season
             WHERE season.endAt <= :now
               AND (
                   season.status IN ('ACTIVE', 'SCHEDULED', 'CLOSING')
                   OR (
                       season.status = 'ENDED'
                       AND NOT EXISTS (
                           SELECT execution.id
                             FROM SchedulerExecution execution
                            WHERE execution.jobType = 'SEASON_CLOSE'
                              AND execution.scheduledFor = season.endAt
                              AND execution.status = 'SUCCEEDED'
                       )
                   )
               )
             ORDER BY season.endAt ASC, season.id ASC
            """)
    List<Long> findIdsNeedingCloseNormalization(@Param("now") Instant now);

    /**
     * 첫 시즌을 배타 잠금하여 모든 시즌 생성 작업을 직렬화합니다.
     * 선행 SELECT 없이 이 잠금 조회를 최초 DB 접근으로 사용해야 합니다.
     * Pageable은 PageRequest.of(0, 1)을 전달합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Season s ORDER BY s.startAt ASC")
    List<Season> lockFirstSeasonForCreation(Pageable pageable);

    /**
     * 가장 최근에 저장된 시즌을 확인합니다.
     * 최초 시즌 행을 잠근 후 호출해야 다른 생성 작업과 경합하지 않습니다.
     * 최신 시즌의 ENDED 여부로 새 시즌을 생성할 수 있는지 결정합니다.
     */
    Optional<Season> findFirstByOrderByStartAtDesc();

}
