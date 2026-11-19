package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 그룹 말풍선 메시지의 서버 검증 원본을 24시간 보존합니다. */
@Getter
@Entity
@Table(name = "chat_messages", indexes =
        @Index(name = "ix_chat_messages_expires", columnList = "expires_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @Column(name = "message_id", length = 36, nullable = false, updatable = false)
    private String messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User senderUser;

    @Column(name = "sender_nickname", length = 20, nullable = false)
    private String senderNickname;

    @Column(name = "content", length = 80, nullable = false)
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 인증 사용자와 현재 그룹을 기준으로 서버 식별 메시지를 생성합니다. */
    public static ChatMessage create(
            Group group,
            User sender,
            String senderNickname,
            String content,
            Instant now
    ) {
        ChatMessage message = new ChatMessage();
        message.messageId = UUID.randomUUID().toString();
        message.group = group;
        message.senderUser = sender;
        message.senderNickname = senderNickname;
        message.content = content;
        message.sentAt = now;
        message.expiresAt = now.plusSeconds(24 * 60 * 60);
        return message;
    }
}
