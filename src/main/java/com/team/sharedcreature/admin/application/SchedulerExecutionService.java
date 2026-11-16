package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.infrastructure.SchedulerExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Scheduler 실행권 획득 절차를 조정하는 서비스.
 * 최초 등록과 기존 실행 재선점을 독립 트랜잭션에 위임함.
 * 특정 DBMS 전용 UPSERT를 사용하지 않음.
 */
@Slf4j
@Service
public class SchedulerExecutionService {

    private final SchedulerExecutionRepository repository;
    private final SchedulerClaimTransactionService transactionService;

    public SchedulerExecutionService(SchedulerExecutionRepository repository, SchedulerClaimTransactionService transactionService) {
        this.repository = repository;
        this.transactionService = transactionService;
    }

    /**
     * 지정한 논리 작업의 실행권 획득을 시도함.
     * 기존 행이 있으면 잠금 기반 재선점을 진행함.
     * 최초 INSERT 경합이 발생하면 롤백 후 새 트랜잭션으로 재조회함.
     */
    public Optional<SchedulerClaimTicket> tryClaim(
            String jobType,
            Instant scheduledFor,
            String ownerInstanceId,
            Duration leaseDuration
    ) {
        validateInput(jobType, scheduledFor, ownerInstanceId, leaseDuration);

        // 이미 존재하는 작업은 불필요한 INSERT 충돌을 발생시키지 않음
        if (repository.existsByJobTypeAndScheduledFor(jobType, scheduledFor)) {
            return transactionService.reclaimExisting(jobType, scheduledFor, ownerInstanceId, leaseDuration);
        }

        try {

            // 최초 INSERT는 별도 트랜잭션에서 Commit
            return Optional.of(transactionService.insertNew(jobType, scheduledFor, ownerInstanceId, leaseDuration));

        } catch (DataIntegrityViolationException e) {

            // 동일 UNIQUE 키를 누가 먼저 INSERT 했는지 확인
            if (! repository.existsByJobTypeAndScheduledFor(jobType, scheduledFor)) {
                throw e;
            } else {
                // 실패. INSERT 트랜잭션은 이미 롤백됨

                return transactionService.reclaimExisting(jobType, scheduledFor, ownerInstanceId, leaseDuration);
            }

        }
    }

    /**
     * DB에 저장할 실행권 요청의 입력값 검증
     */
    private void validateInput(String jobType, Instant scheduledFor, String ownerInstanceId, Duration leaseDuration) {

        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(leaseDuration, "leaseDuration");

        if (jobType == null || jobType.isBlank() || jobType.length() > 50) {
            throw new IllegalArgumentException("jobType 입력값 형식이 맞지 않습니다.");
        }

        if (ownerInstanceId == null || ownerInstanceId.isBlank() || ownerInstanceId.length() > 100) {
            throw new IllegalArgumentException("ownerInstanceId 입력값 형식이 맞지 않습니다.");
        }

        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Lease 기간은 양수이어야 합니다.");
        }
    }

}
