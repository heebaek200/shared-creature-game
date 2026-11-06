package com.team.sharedcreature.admin.infrastructure;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * SchedulerExecution 엔티티의 기본 영속성 연산을 제공합니다.
 * 최초 INSERT는 JpaRepository의 saveAndFlush를 사용합니다.
 * 재선점 시에는 비관적 쓰기 잠금으로 동시 변경을 차단합니다.
 */
public interface SchedulerExecutionRepository extends JpaRepository<SchedulerExecution, Long> {

    /**
     * 동일한 논리 작업의 실행 이력이 존재하는지 확인함.
     * 성능 최적화 목적이며, 동시성 보장 수단은 아님. 실제 동시성은 DB UNIQUE 제약으로 최종 방어.
     */
    boolean existsByJobTypeAndScheduledFor(String jobType, Instant scheduledFor);

    /**
     * 기존 실행 이력을 배타 잠금으로 조회함.
     * 같은 논리 작업에 대한 재선점 요청을 직렬화.
     * 트랜잭션이 종료될 때 DB 잠금이 해제됨.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT
                e
            FROM
                SchedulerExecution AS e
            WHERE
                e.jobType      = :jobType
            AND e.scheduledFor = :scheduledFor
    """)
    Optional<SchedulerExecution> findForUpdate(
            @Param("jobType") String jobType,
            @Param("scheduledFor") Instant scheduledFor
    );

    /**
     * 실행 이력ID로 행을 배타 잠금하여 조회한다.
     * Heartbeat, 성공, 실패 기록의 동시 변경을 직렬화.
     * 잠금은 해당 트랜잭션의 Commit 또는 Rollback까지 유지됨.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT
            e
        FROM
            SchedulerExecution AS e
        WHERE
            e.id = :id
    """)
    Optional<SchedulerExecution> findForUpdateById(@Param("id") Long id);
}
