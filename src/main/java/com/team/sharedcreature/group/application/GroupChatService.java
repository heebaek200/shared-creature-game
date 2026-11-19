package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.realtime.GroupChatEventPublisher;
import com.team.sharedcreature.group.api.GroupChatMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 말풍선 채팅 입력 검증·전송 제한·저장·발행을 조합합니다. */
@Slf4j
@Service
public class GroupChatService {

    private final GroupChatRateLimiter rateLimiter;
    private final GroupChatTransactionService transactionService;
    private final GroupChatEventPublisher eventPublisher;

    /** 전송 제한, 저장 트랜잭션과 Best Effort 발행 경계를 주입받습니다. */
    public GroupChatService(
            GroupChatRateLimiter rateLimiter,
            GroupChatTransactionService transactionService,
            GroupChatEventPublisher eventPublisher
    ) {
        this.rateLimiter = rateLimiter;
        this.transactionService = transactionService;
        this.eventPublisher = eventPublisher;
    }

    /** 서버 기준으로 본문을 정제하고 Commit된 메시지만 성공 전송으로 기록합니다. */
    public GroupChatMessageResponse create(Long userId, String rawContent) {
        String content = normalize(rawContent);
        GroupChatRateLimiter.Permit permit = rateLimiter.acquire(userId);
        boolean committed = false;
        try {
            GroupChatMessageResponse response = transactionService.create(userId, content);
            committed = true;
            rateLimiter.finish(permit, true);
            try {
                eventPublisher.publish(response);
            } catch (RuntimeException exception) {
                // 저장 Commit 뒤 Broker 장애가 발생해도 REST 성공 계약은 유지합니다.
                log.warn("저장된 채팅 메시지의 STOMP 발행에 실패했습니다. messageId={}",
                        response.messageId(), exception);
            }
            return response;
        } finally {
            if (!committed) {
                rateLimiter.finish(permit, false);
            }
        }
    }

    private String normalize(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        String normalized = trimUnicode(value);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 80 || normalized.codePoints().anyMatch(this::isForbidden)) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        return normalized;
    }

    private String trimUnicode(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private boolean isForbidden(int codePoint) {
        return Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR;
    }
}
