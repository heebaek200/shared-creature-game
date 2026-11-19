package com.team.sharedcreature.group.application;

import com.team.sharedcreature.group.infrastructure.ChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 지정 시각까지 만료된 채팅 원본을 물리 삭제합니다. */
@Service
public class ChatMessageCleanupService {

    private final ChatMessageRepository repository;

    /** 만료 메시지를 삭제할 저장소를 주입받습니다. */
    public ChatMessageCleanupService(ChatMessageRepository repository) {
        this.repository = repository;
    }

    /** 기준 시각에 만료된 모든 채팅 원본을 삭제하고 삭제 건수를 반환합니다. */
    @Transactional
    public int deleteExpired(Instant now) {
        return repository.deleteExpired(now);
    }
}
