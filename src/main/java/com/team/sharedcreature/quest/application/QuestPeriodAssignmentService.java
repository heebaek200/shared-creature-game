package com.team.sharedcreature.quest.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * 퀘스트 기간 배정의 성공·실패 트랜잭션을 순서대로 조정합니다.
 * 실패 원인을 기록하기 전에 성공 트랜잭션이 완전히 롤백되도록 분리합니다.
 * 4-F-4에서 기동·자정·5분 정상화의 공통 진입점으로 사용합니다.
 */
@Service
public class QuestPeriodAssignmentService {

    private static final int MAX_LOCK_ATTEMPTS = 3;

    private final QuestPeriodPersistenceService persistence;

    /**
     * 배정 저장 및 독립 실패 기록을 담당하는 서비스를 주입합니다.
     * 자체 트랜잭션을 열지 않으므로 롤백된 커넥션을 재사용하지 않습니다.
     * 외부 스케줄러의 재시도와 중복 실행에 대비합니다.
     */
    public QuestPeriodAssignmentService(QuestPeriodPersistenceService persistence) {
        this.persistence = persistence;
    }

    /**
     * 현재 유효 기간에만 배정을 시도하고 이미 확정된 배정은 그대로 반환합니다.
     * 선정 실패를 별도 트랜잭션에서 FAILED 상태로 남깁니다.
     * 기간 UNIQUE 경합만 기존 상태 재조회로 처리하고 다른 영속화 오류는 전파합니다.
     */
    public Result assign(QuestPeriodCalendar.PeriodWindow window) {
        Objects.requireNonNull(window, "window");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("퀘스트 배정 조정은 트랜잭션 밖에서 호출해야 합니다.");
        }

        // 매번 독립된 REQUIRES_NEW 트랜잭션에서 시도하여 롤백된 세션을 재사용하지 않습니다.
        for (int attempt = 1; attempt <= MAX_LOCK_ATTEMPTS; attempt++) {
            try {
                return assignOnce(window);
            } catch (PessimisticLockingFailureException conflict) {
                // Deadlock/Lock 획득 실패는 일시적이므로 최대 3번만 재시도합니다.
                if (attempt == MAX_LOCK_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("퀘스트 배정 재시도 횟수 계산 오류");
    }

    /**
     * 신규 배정과 기존 기간 재조회를 한 번씩 시도합니다.
     * 선택 실패는 별도 트랜잭션에서 FAILED로 기록합니다.
     * UNIQUE 충돌은 상대 트랜잭션이 확정한 결과를 재조회해 판단합니다.
     */
    private Result assignOnce(QuestPeriodCalendar.PeriodWindow window) {
        try {
            // ACTIVE/FAILED 판정 및 배정 전체 INSERT는 별도 Commit으로 처리됩니다.
            return persistence.assign(window);
        } catch (QuestTemplateSelectionService.SelectionException failure) {
            return recordAfterRollback(window, failure);
        } catch (QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException expired) {
            // REQUIRES_NEW가 롤백된 다음 만료 기간을 정상적인 미처리 상태로 변환합니다.
            return new Result(Outcome.NOT_READY, null, 0);
        } catch (DataIntegrityViolationException conflict) {
            // 기간 UNIQUE 경합에서 승자가 Commit한 경우만 정상적인 중복 실행으로 처리합니다.
            return persistence.readCommittedOutcome(window).orElseThrow(() -> conflict);
        }
    }

    /**
     * 선정 실패 후 새로운 트랜잭션으로 실패 상태를 보존합니다.
     * 다른 실행자가 먼저 ACTIVE 또는 FAILED를 확정한 UNIQUE 경합은 재조회합니다.
     * 실패 기록 자체가 실패한 비즈니스 오류를 성공으로 위장하지 않습니다.
     */
    private Result recordAfterRollback(
            QuestPeriodCalendar.PeriodWindow window,
            QuestTemplateSelectionService.SelectionException failure
    ) {
        try {
            return persistence.recordSelectionFailure(window,
                    failure.failureCode().name(), failure.getMessage());
        } catch (QuestPeriodPersistenceService.WindowExpiredDuringPersistenceException expired) {
            // 실패 이력을 쓰는 중 만료되더라도 해당 기간에 FAILED 행을 남기지 않습니다.
            return new Result(Outcome.NOT_READY, null, 0);
        } catch (DataIntegrityViolationException conflict) {
            return persistence.readCommittedOutcome(window).orElseThrow(() -> conflict);
        }
    }

    /**
     * 현재 기간의 정상화 결과를 호출자에게 알립니다.
     * CREATED는 새 Snapshot 확정, ALREADY_ACTIVE는 멱등 재실행입니다.
     * NOT_READY는 시즌·시간 경계 제외, FAILED는 운영자가 확인할 선정 실패입니다.
     */
    public enum Outcome { CREATED, ALREADY_ACTIVE, NOT_READY, FAILED }

    /**
     * 결과는 기간의 상태와 저장된 배정 건수를 구분합니다.
     * FAILED의 periodId는 진단용으로 남긴 FAILED 행 ID입니다.
     * 스케줄 실행권과 누락 기간 요약은 후속 4-F-4에서 연결합니다.
     */
    public record Result(Outcome outcome, Long periodId, long assignmentCount) {
    }
}
