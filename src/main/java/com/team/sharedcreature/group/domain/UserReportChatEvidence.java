package com.team.sharedcreature.group.domain;

import com.team.sharedcreature.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 채팅 신고 접수 시 서버가 검증한 불변 메시지 Snapshot입니다. */
@Getter
@Entity
@Table(name = "user_report_chat_evidence")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserReportChatEvidence {

    @Id
    @Column(name = "report_id")
    private Long reportId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private UserReport report;

    @Column(name = "chat_message_id", length = 36, nullable = false)
    private String chatMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User senderUser;

    @Column(name = "sender_nickname", length = 20, nullable = false)
    private String senderNickname;

    @Column(name = "content", length = 80, nullable = false)
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 원본 메시지와 신고를 연결하되 원본 삭제 후에도 Snapshot을 유지합니다. */
    public static UserReportChatEvidence preserve(UserReport report, ChatMessage message, Instant now) {
        UserReportChatEvidence evidence = new UserReportChatEvidence();
        evidence.report = report;
        evidence.chatMessageId = message.getMessageId();
        evidence.senderUser = message.getSenderUser();
        evidence.senderNickname = message.getSenderNickname();
        evidence.content = message.getContent();
        evidence.sentAt = message.getSentAt();
        evidence.createdAt = now;
        return evidence;
    }
}
