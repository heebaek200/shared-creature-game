package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.domain.SchedulerExecution;
import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Scheduler 실행권 변경을 독립 DB 트랜잭션에서 수행함.
 * 신규 INSERT와 기존 행 재선점을 서로 다른 호출로 분리함.
 * 트랜잭션 경계 밖의 조정 Service에서 호출하도록 설계함.
 */
@Slf4j
@Service
public class SchedulerClaimTransactionService {

    private final SchedulerExecutionRepository repository;
    private final Clock clock;

    public SchedulerClaimTransactionService(SchedulerExecutionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 새로운 논리 작업을 INSERT하여 실행권을 획득.
     * UNIQUE 충돌은 호출자에게 전파하고 이 트랜잭션을 롤백함.
     * 정상 Commit 후에만 실행권 정보가 호출자에게 반환됨.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)  // 이미 진행 중인 트랜잭션이 있든 없든 상관없이, 해당 메서드가 호출되면 새로운 DB 커넥션과 물리적 트랜잭션을 시작
    public SchedulerClaimTicket insertNew(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration
    ) {
        Instant now = Instant.now(clock);

        SchedulerExecution execution = SchedulerExecution.startNew(
                jobType,
                scheduledFor,
                ownerInstanceId,
                now,
                leaseDuration
        );

        // INSERT를 즉시 Flush하여 DB UNIQUE 제약을 확인함.
        SchedulerExecution saved = repository.saveAndFlush(execution);

        return toTicket(saved);
    }

    /**
     * 기존 실행 이력을 잠금 뒤 재선점을 시도.
     * FAILED 또는 Lease 만료 RUNNING만 실행권을 재발급. 이외(유효한 RUNNING과 SUCCEEDED)는 빈 결과 반환.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SchedulerClaimTicket> reclaimExisting(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration
    ) {
        // DB 잠금 획득 후 현재 상태를 재확인
        SchedulerExecution execution = repository.findForUpdate(jobType, scheduledFor)
                .orElseThrow(() -> new IllegalStateException("실행 중인 스케쥴러를 찾을 수 없습니다."));

        // 잠금 대기 후이므로 시각을 다시 읽음
        Instant now = Instant.now(clock);

        if (! execution.canReclaim(now)) {
            return Optional.empty();
        }

        // 새로운 시도 번호와 소유자를 저장
        execution.reclaim(ownerInstanceId, now, leaseDuration);

        // 트랜잭션 커밋할 때 더티 체킹으로 반영
        return Optional.of(toTicket(execution));
    }

    /**
     * 실행 엔티티를 외부 전달용 실행권으로 변환함
     */
    private SchedulerClaimTicket toTicket(SchedulerExecution execution) {
        return new SchedulerClaimTicket(
                execution.getId(),
                execution.getJobType(),
                execution.getScheduledFor(),
                execution.getOwnerInstanceId(),
                execution.getAttemptCount()
        );
    }

}
