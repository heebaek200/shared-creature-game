package com.team.sharedcreature.payment.application;

/**
 * #22 환불 상태 복구 서비스와 #24 Scheduler 사이의 구현 계약.
 * 이 인터페이스의 구현체는 #22가 최종적으로 제공하여야 합니다.
 * 최신 PG 조회, 환불 판정, 아이템 회수는 #24에서 중복 구현하지 않습니다.
 */
public interface RefundRecoveryWork {

    /**
     * 5분 이상 진행 중이거나 결과 불명인 환불을 최대 100건 복구합니다.
     * PG에 새 취소 요청을 반복 송신하지 않고 최신 상태만 조회해야 합니다.
     * 전달받은 heartbeat를 단건 처리 사이에 주기적으로 호출해야 합니다.
     * @param heartbeat 현재 #24 실행권의 Lease를 갱신하는 콜백
     * @return 유형별 처리 결과. 상태가 확정되지 않은 건과 기술 오류는 구별합니다.
     */
    RecoverySummary recoverStaleRefunds(Runnable heartbeat);

    /**
     * 환불 복구 대상별 결과를 구분하는 실행 요약힙니다.
     * definitiveFailedCount : PG에서 실패가 확정된 건수이므로 기술 오류가 아닙니다.
     * errorCount : DB·PG 장애 등으로 복구 업무 자체가 실패한 건수입니다.
     */
    record RecoverySummary(
            int selectedCount,
            int completedCount,
            int definitiveFailedCount,
            int pendingCount,
            int unknownCount,
            int skippedCount,
            int errorCount
    ) {
        /**
         * 잘못된 음수 건수가 기록되지 않도록 생성 시 검증합니다.
         * 총합 및 배치 상한은 Scheduler의 정상화 단계에서도 검증합니다.
         */
        public RecoverySummary {
            // null을 허용하지 않는 기본 int로 결과 항목을 표현합니다.
            if (selectedCount < 0 || completedCount < 0 || definitiveFailedCount < 0
                    || pendingCount < 0 || unknownCount < 0 || skippedCount < 0 || errorCount < 0) {
                throw new IllegalArgumentException("환불 복구 건수는 음수가 될 수 없습니다.");
            }
        }
    }

}
