package com.team.sharedcreature._core.api;

import java.time.Instant;

/**
 * 모든 성공 응답에 서버 처리 시각을 제공합니다.
 *
 * @param serverTime 응답을 생성한 UTC 시각
 */
public record ApiMeta(Instant serverTime) {
}
