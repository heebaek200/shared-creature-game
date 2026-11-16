package com.team.sharedcreature.admin.application;

import java.time.Instant;

/**
 * Scheduler가 획득한 실행권을 표현하는 레코드.
 * 실행 이력 ID와 시도 번호로 현재 실행을 식별한다.
 * Heartbeat, 성공, 실패 처리에서 소유권 검증에 사용한다.
 * @param executionId 실행 이력 ID
 * @param jobType 작업 종류
 * @param scheduledFor 논리 실행 시각
 * @param ownerInstanceId 실행 인스턴스
 * @param attemptCount 선점 시도 번호
 */
public record SchedulerClaimTicket (
        Long executionId,
        String jobType,
        Instant scheduledFor,
        String ownerInstanceId,
        int attemptCount
) {}
