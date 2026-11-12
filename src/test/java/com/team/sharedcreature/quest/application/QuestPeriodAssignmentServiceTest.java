package com.team.sharedcreature.quest.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 퀘스트 배정 조정자가 성공과 실패를 각각 올바른 트랜잭션 경로로 전달하는지 확인합니다.
 * DB를 사용하지 않고 Repository 영속화 구현과 예외 경계를 분리해 검증합니다.
 * 실제 UNIQUE 제약과 Snapshot 저장은 별도 MySQL 통합 테스트에서 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class QuestPeriodAssignmentServiceTest {

    @Mock private QuestPeriodPersistenceService persistence;
    private QuestPeriodAssignmentService service;
    private final QuestPeriodCalendar.PeriodWindow window = new QuestPeriodCalendar.PeriodWindow(
            "DAILY", LocalDate.of(2026, 11, 3),
            Instant.parse("2026-11-02T15:00:00Z"),
            Instant.parse("2026-11-03T15:00:00Z"), 1);

    /**
     * 예외 처리를 포함한 조정 서비스에 실제 DB 대신 Mock을 연결합니다.
     * 개별 테스트마다 Mock 호출 상태가 초기화됩니다.
     * 스케줄 실행권·트랜잭션 프록시는 여기서 검증하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        service = new QuestPeriodAssignmentService(persistence);
    }

    /**
     * 성공한 저장 결과는 다른 트랜잭션을 시작하지 않고 그대로 전달합니다.
     * 실패 기록 서비스가 불필요하게 호출되지 않아야 합니다.
     * 이미 저장된 Snapshot을 다시 만들지 않습니다.
     */
    @Test
    void forwardsSuccessfulAssignment() {
        var result = new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.CREATED, 10L, 3);
        when(persistence.assign(window)).thenReturn(result);
        assertSame(result, service.assign(window));
        verify(persistence).assign(window);
        verifyNoMoreInteractions(persistence);
    }

    /**
     * 템플릿 부족으로 실패하면 원래 저장 트랜잭션이 종료된 뒤 실패 기록을 호출합니다.
     * 원인 코드를 문자열로 보존하고 메시지도 함께 전달합니다.
     * 실패 결과는 API나 Scheduler가 별도로 처리하도록 반환합니다.
     */
    @Test
    void recordsSelectionFailure() {
        when(persistence.assign(window)).thenThrow(new QuestTemplateSelectionService.SelectionException(
                QuestTemplateSelectionService.FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES, "LOW 부족"));
        var failed = new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.FAILED, 20L, 0);
        when(persistence.recordSelectionFailure(window, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족"))
                .thenReturn(failed);
        assertSame(failed, service.assign(window));
        verify(persistence).recordSelectionFailure(window, "INSUFFICIENT_ACTIVE_TEMPLATES", "LOW 부족");
    }

    /**
     * 두 실행자가 같은 기간을 INSERT해 UNIQUE 경합이 발생하면 확정 행을 재조회합니다.
     * 이미 ACTIVE인 기간의 ID를 재사용해야 Snapshot이 중복되지 않습니다.
     * DB에 확정 행이 없는 다른 무결성 오류는 숨기지 않습니다.
     */
    @Test
    void resolvesUniqueConflictFromCommittedPeriod() {
        when(persistence.assign(window)).thenThrow(new DataIntegrityViolationException("unique conflict"));
        var existing = new QuestPeriodAssignmentService.Result(
                QuestPeriodAssignmentService.Outcome.ALREADY_ACTIVE, 42L, 3);
        when(persistence.readCommittedOutcome(window)).thenReturn(java.util.Optional.of(existing));
        assertSame(existing, service.assign(window));
    }

    /**
     * UNIQUE 충돌 외의 영속화 오류까지 성공으로 위장하지 않게 합니다.
     * 기존 기간이 확인되지 않는다면 최초 예외를 전파합니다.
     * DB 장애나 FK 위반을 잘못된 정상 중복 실행으로 분류하지 않습니다.
     */
    @Test
    void propagatesUnresolvedIntegrityError() {
        when(persistence.assign(window)).thenThrow(new DataIntegrityViolationException("unresolved"));
        when(persistence.readCommittedOutcome(window)).thenReturn(java.util.Optional.empty());
        assertThrows(DataIntegrityViolationException.class, () -> service.assign(window));
    }
}
