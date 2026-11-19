package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.ChatMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** 채팅 원본 저장·잠금 조회와 만료 삭제를 제공합니다. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT message FROM ChatMessage message WHERE message.messageId = :messageId")
    Optional<ChatMessage> findByMessageIdForUpdate(@Param("messageId") String messageId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatMessage message WHERE message.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
