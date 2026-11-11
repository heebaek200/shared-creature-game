package com.team.sharedcreature.quest.application;

import java.time.Instant;

/**
 * 사진 정리 업무와 #24 Scheduler 실행권 사이의 계약입니다.
 * Scheduler는 파일 경로나 EXIF 검증 코드를 직접 다루지 않습니다.
 * 실제 구현은 PhotoSubmissionCleanupWork가 담당합니다.
 */
public interface PhotoRetentionWork {

    /**
     * 현재 시각에서 만료된 사진 제출과 파일·좌표를 정리합니다.
     * 파일 삭제 등 외부 I/O 중에는 DB 트랜잭션을 유지하지 않아야 합니다.
     * 완료된 항목은 재호출 시 중복 변경하지 않도록 구현해야 합니다.
     */
    Summary cleanup(Instant now);

    /**
     * 한 실행에서 실제로 처리된 항목과 실패·잔여 가능성을 기록합니다.
     * 실패가 하나라도 있으면 Scheduler 실행 이력을 FAILED로 남깁니다.
     * 잔여 가능성은 다음 5분 경계에서 추가 처리하도록 알립니다.
     */
    record Summary(int pendingInvalidated, int invalidFilesDeleted,
                   int validFilesDeleted, int failedCount, boolean moreMayRemain) {
        public Summary {
            if (pendingInvalidated < 0 || invalidFilesDeleted < 0 || validFilesDeleted < 0 || failedCount < 0) {
                throw new IllegalArgumentException("사진 정리 건수는 음수가 될 수 없습니다.");
            }
        }
    }
}
