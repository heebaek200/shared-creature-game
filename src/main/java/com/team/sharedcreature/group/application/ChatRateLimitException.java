package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;

/** 채팅 429 응답에 필요한 지연 초를 보존합니다. */
public class ChatRateLimitException extends BusinessException {

    private final long retryAfterSeconds;

    public ChatRateLimitException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
