// @ts-check
import { apiRequest } from '../../api/client.js';

/** 현재 사용자의 일일·주간 퀘스트를 조회합니다. */
export function listCurrentQuests() {
  return apiRequest('/api/v1/quests/current');
}

/** SELF_CHECK 퀘스트를 완료하고 확정된 보상 결과를 반환합니다. */
export function completeQuestAssignment(assignmentId) {
  return apiRequest(
      `/api/v1/quest-assignments/${encodeURIComponent(assignmentId)}/completions`,
      { method: 'POST' },
  );
}

/** 오늘의 서비스 체크인을 기록하고 자동 완료 결과를 반환합니다. */
export function createCheckIn() {
  return apiRequest('/api/v1/check-ins', { method: 'POST' });
}

/** 사진 퀘스트 JPEG를 Multipart로 제출합니다. */
export function submitQuestPhoto(assignmentId, file) {
  const multipart = new FormData();
  multipart.append('assignmentId', String(assignmentId));
  multipart.append('file', file);

  return apiRequest('/api/v1/photo-submissions', {
    method: 'POST',
    multipart,
  });
}
