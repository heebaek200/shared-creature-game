// @ts-check

const FALLBACK_MESSAGES = {
  FILE_TOO_LARGE: '사진은 최대 10 MiB까지 제출할 수 있습니다.',
  PHOTO_ATTEMPT_LIMIT_EXCEEDED: '이 퀘스트의 사진 제출 가능 횟수를 모두 사용했습니다.',
  RATE_LIMIT_EXCEEDED: '사진 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  QUEST_PERIOD_ENDED: '퀘스트 기간이 종료되었습니다.',
  SEASON_NOT_ACTIVE: '현재 진행 중인 시즌이 아닙니다.',
  GROUP_SEASON_ENDED: '그룹 시즌이 종료되었습니다.',
  QUEST_COMPLETION_NOT_ALLOWED: '이 퀘스트는 해당 방식으로 직접 완료할 수 없습니다.',
  CONCURRENT_MODIFICATION: '다른 요청으로 상태가 변경되었습니다.',
};

/**
 * 서버가 안전하게 제공한 업무 메시지를 우선하고 응답 메시지가 없을 때만 코드 문구를 사용합니다.
 *
 * @param {{code?: string, message?: string}|null|undefined} error API 또는 사진 선택 오류
 * @returns {string} 사용자에게 표시할 오류 문구
 */
export function questErrorMessage(error) {
  return error?.message
    ?? FALLBACK_MESSAGES[error?.code]
    ?? '요청을 처리하지 못했습니다.';
}
