package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * QuestPeriod 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestPeriodRepository extends JpaRepository<QuestPeriod, Long> {

    /**
     * 기간의 상태 전이 전에 배타 잠금을 획득합니다.
     * 시즌 공유 잠금을 먼저 가진 호출자가 이 메서드를 사용합니다.
     * JOIN 없이 quest_periods 행 하나만 잠그도록 구성합니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
              FROM QuestPeriod p
             WHERE p.periodType = :periodType
               AND p.startAt = :startAt
            """)
    Optional<QuestPeriod> findForUpdateByPeriodTypeAndStartAt(
            @Param("periodType") String periodType,
            @Param("startAt") Instant startAt
    );

    /**
     * UNIQUE 충돌로 다른 트랜잭션이 확정한 기간을 새 트랜잭션에서 조회합니다.
     * 상태 판정은 단일 기간 행에만 의존하도록 제한합니다.
     * 배정 상세는 별도 Repository에서 읽습니다.
     */
    Optional<QuestPeriod> findByPeriodTypeAndStartAt(String periodType, Instant startAt);

    /**
     * 첫 시즌 이후 이미 저장된 만료 퀘스트 기간의 시작 시각을 조회합니다.
     * 현재 기간은 제외하며 기록이 없는 이전 기간을 소급 생성하지 않습니다.
     * 존재하는 기간의 상태와 관계없이 누락 집계에서 제외합니다.
     */
    @Query("""
            SELECT p.startAt
              FROM QuestPeriod p
             WHERE p.periodType = :periodType
               AND p.startAt >= :firstStart
               AND p.startAt < :currentStart
             ORDER BY p.startAt ASC
            """)
    List<Instant> findHistoricalStartAts(
            @Param("periodType") String periodType,
            @Param("firstStart") Instant firstStart,
            @Param("currentStart") Instant currentStart
    );

    /**
     * 퀘스트 기간 정보를 조회하고 저장합니다.
     * 현재 시각에 진행 중인 퀘스트 기간을 시즌과 기간 유형으로 조회합니다.
     *
     * @param seasonId 현재 활성 시즌 ID
     * @param periodType 조회할 기간 유형 (일일, 주간)
     * @param now 현재 시각
     * @return 조건에 맞는 진행 중 기간. 기간이 없으면 빈 Optional
     */
    @Query("""
            SELECT p
              FROM QuestPeriod p
              JOIN FETCH p.season s
             WHERE s.id = :seasonId
               AND s.status = 'ACTIVE'
               AND p.status = 'ACTIVE'
               AND p.periodType = :periodType
               AND p.startAt <= :now
               AND p.endAt > :now
             ORDER BY p.startAt DESC
            """)
    Optional<QuestPeriod> findCurrentBySeasonAndType(
            @Param("seasonId") Long seasonId,
            @Param("periodType") String periodType,
            @Param("now") Instant now
    );
}
