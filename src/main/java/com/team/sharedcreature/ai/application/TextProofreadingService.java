package com.team.sharedcreature.ai.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
public class TextProofreadingService {

    private final TextProofreader textProofreader;
    private final RequestRateLimiter requestRateLimiter;


    public TextProofreadingService(TextProofreader textProofreader, RequestRateLimiter requestRateLimiter) {
        this.textProofreader = textProofreader;
        this.requestRateLimiter = requestRateLimiter;
    }

    public record ProofreadingResult(
            String originalText,
            String suggestedText,
            boolean changed
    ) {
    }

    public ProofreadingResult proofread(Long userId, String text) {

        String normalizedText = text.trim();

        if (normalizedText.isEmpty() || normalizedText.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }

        RequestRateLimiter.LimitResult limitResult = requestRateLimiter.acquire(userId);

        if (limitResult != RequestRateLimiter.LimitResult.ACQUIRED) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        try {
            String suggestedText = textProofreader.proofread(normalizedText);
            return new ProofreadingResult(normalizedText, suggestedText, !normalizedText.equals(suggestedText));

        } catch (RestClientException | IllegalStateException exception) {
            log.warn("AI 교정 처리 실패. errorType={}",
                    exception.getClass().getSimpleName());

            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        } finally {
            requestRateLimiter.release();
        }
    }
}








