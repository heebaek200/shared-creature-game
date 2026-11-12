package com.team.sharedcreature._core.realtime;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 단일 서버에서 그룹별 실시간 Stream 순번을 관리합니다. */
@Service
public class GroupLiveStreamService {

    private final UUID streamInstanceId = UUID.randomUUID();
    private final ConcurrentHashMap<Long, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** 서버 실행 인스턴스의 고정 Stream ID를 반환합니다. */
    public UUID streamInstanceId() {
        return streamInstanceId;
    }

    /** 그룹의 다음 이벤트 순번을 원자적으로 발급합니다. */
    public long nextSequence(Long groupId) {
        return sequences.computeIfAbsent(groupId, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** 그룹에서 현재까지 발급한 마지막 이벤트 순번을 반환합니다. */
    public long currentSequence(Long groupId) {
        AtomicLong sequence = sequences.get(groupId);
        return sequence == null ? 0L : sequence.get();
    }
}
