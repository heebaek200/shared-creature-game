package com.team.sharedcreature.payment.application;

import com.team.sharedcreature.payment.domain.PgApiAttempt;
import com.team.sharedcreature.payment.infrastructure.PgApiAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Scheduler가 선택한 환불의 복구 처리권과 조회 이력을 준비합니다.
 * 외부 PG 호출은 이 트랜잭션이 Commit된 뒤 수행해야 합니다.
 */
@RequiredArgsConstructor
@Service
public class RefundRecoveryPreparationService {

    private final RefundLockService refundLockService;
    private final PgApiAttemptRepository pgApiAttemptRepository;
    private final Clock clock;
    private final RefundRequestRepository refundRequestRepository;

    /**
     * 취소 처리권 선점 후 5분 이상 지난 환불 ID를 최대 100건 조회합니다.
     * 후보 조회는 단건 복구 처리와 별도의 짧은 트랜잭션으로 끝냅니다.
     *
     * @return 오래된 순서로 정렬된 복구 후보 ID, 대상이 없으면 빈 목록
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public List<Long> findScheduledCandidates() {
        // 서버 기본 시간대가 아닌 주입된 Clock으로 경과 시간을 계산합니다.
        Instant cutoff = clock.instant().minusSeconds(300);

        // 첫 페이지 100건만 조회하며 전체 건수 계산은 하지 않습니다.
        return refundRequestRepository.findStaleCancellationIds(
                cutoff,
                PageRequest.of(0, 100)
        );
    }

    /**
     * 잠금 획득 후 복구 조건을 재검사하고 조회 정보를 반환합니다.
     *
     * @param requestId 복구 후보 환불 요청 ID
     * @return 조회 준비 정보, 다른 작업이 처리했거나 선점했다면 빈 Optional
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RecoveryCommand> prepareScheduled(Long requestId) {
        // Scheduler는 단건마다 별도 트랜잭션으로 준비합니다.
        return prepareWithinTransaction(requestId);
    }

    /**
     * 기존 트랜잭션 안에서 5분 경과 조건으로 복구를 준비합니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecoveryCommand> prepareWithinTransaction(Long requestId) {
        return prepareRecovery(requestId, false);
    }

    /**
     * 관리자 요청의 트랜잭션 안에서 복구를 준비합니다.
     * 결과 불명은 즉시 조회하되 진행 중인 PG 호출과 중복되지 않게 검사합니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecoveryCommand> prepareManualWithinTransaction(Long requestId) {
        return prepareRecovery(requestId, true);
    }

    /**
     * Scheduler와 관리자 요청이 공유하는 잠금·선점·이력 저장 로직입니다.
     */
    private Optional<RecoveryCommand> prepareRecovery(
            Long requestId,
            boolean allowImmediateUnknown
    ) {
        // 기존 장착·환불 처리와 같은 순서로 잠급니다.
        var locked = refundLockService.lock(requestId);
        var refund = locked.refund();
        var purchase = locked.purchase();

        // 환불 잠금 이후에 PG 취소·조회 이력을 잠급니다.
        var history = pgApiAttemptRepository
                .findCancellationHistoryForUpdate(requestId);

        // 잠금 대기 시간을 포함하도록 잠금 획득 후 현재 시각을 읽습니다.
        Instant now = clock.instant();

        // 아직 끝나지 않은 최근 PG 호출이 있으면 새 조회를 선점하지 않습니다.
        if (!history.isEmpty()) {
            var latest = history.getLast();

            if (latest.getStartedAt() == null) {
                throw new IllegalStateException(
                        "PG 호출 이력의 시작 시각이 없습니다."
                );
            }

            // 정확히 5분이 지났다면 중단된 호출의 복구를 다시 시도할 수 있습니다.
            boolean recentInProgress = latest.getCompletedAt() == null
                    && latest.getStartedAt().isAfter(now.minusSeconds(300));

            if (recentInProgress) {
                return Optional.empty();
            }
        }

        if (!refund.tryClaimRecovery(now, allowImmediateUnknown)) {
            return Optional.empty();
        }

        var cancellation = history.stream()
                .filter(attempt ->
                        "CANCELLATION".equals(attempt.getOperation())
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "복구할 환불의 기존 취소 요청 이력이 없습니다."
                ));

        // 모든 조회 이력이 최초 취소 요청의 멱등 키를 유지하는지 확인합니다.
        boolean differentKey = history.stream()
                .anyMatch(attempt -> !Objects.equals(
                        cancellation.getIdempotencyKey(),
                        attempt.getIdempotencyKey()
                ));

        if (differentKey) {
            throw new IllegalStateException(
                    "동일 환불의 취소·조회 이력에 서로 다른 멱등 키가 있습니다."
            );
        }

        int lastNumber = history.stream()
                .mapToInt(PgApiAttempt::getAttemptNumber)
                .max()
                .orElseThrow();

        // 번호 계산과 저장을 구매 잠금이 유지되는 같은 트랜잭션에서 수행합니다.
        var lookup = pgApiAttemptRepository.save(
                PgApiAttempt.prepareCancellationLookup(
                        refund,
                        cancellation,
                        Math.incrementExact(lastNumber),
                        now
                )
        );

        return Optional.of(new RecoveryCommand(
                refund.getId(),
                purchase.getId(),
                cancellation.getId(),
                lookup.getId(),
                purchase.getOrderNumber(),
                purchase.getPriceKrwSnapshot(),
                purchase.getCurrency(),
                cancellation.getIdempotencyKey(),
                now
        ));
    }

    /**
     * 트랜잭션 밖으로 전달할 최신 상태 조회 정보입니다.
     * JPA 엔티티 대신 식별자와 값만 전달합니다.
     */
    public record RecoveryCommand(
            Long refundRequestId,
            Long purchaseId,
            Long cancellationAttemptId,
            Long lookupAttemptId,
            String paymentId,
            Long amountKrw,
            String currency,
            String pgIdempotencyKey,
            Instant claimedAt
    ) {
    }
}
