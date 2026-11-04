// @ts-check
import { apiRequest } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';
if (import.meta.env.PROD && useMock) throw new Error('Mock API cannot be enabled in production.');

/** 현재 로그인·온보딩 상태를 조회합니다. */
export function getCurrentUser() {
  return useMock ? mockApi.getCurrentUser() : apiRequest('/api/v1/me');
}

/** Google 소셜 로그인 시작 URL을 생성합니다. */
export function createOAuthAttempt() {
  return apiRequest('/api/v1/auth/oauth-attempts', {
    method: 'POST',
    body: { provider: 'GOOGLE', returnRoute: 'DEFAULT' },
  });
}

/** 가입 대기 사용자의 닉네임을 확정합니다. */
export function completeSignup(nickname) {
  return apiRequest('/api/v1/auth/signup', { method: 'POST', body: { nickname } });
}

/**
 * 현재 세션에 저장된 가입 대기 정보를 조회합니다
 */
export function getSignupContext() {
  return useMock
      ? mockApi.getSignupContext()
      : apiRequest('/api/v1/auth/signup-context');
}
