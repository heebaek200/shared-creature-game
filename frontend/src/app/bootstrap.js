// @ts-check
import { createRouter } from './router.js';
import { loginPage } from '../pages/login-page.js';
import { signupPage } from '../pages/signup-page.js';
import { groupSelectPage } from '../pages/group-select-page.js';
import { notFoundPage } from '../pages/not-found-page.js';
import { statusPage } from '../pages/status-page.js';
import { invitePage } from '../pages/invite-page.js';

/**
 * 공통 Route 표와 Page 생명주기를 시작합니다.
 * @param {Element|null} root 앱 Root Element
 */
export function startApplication(root) {
  if (!(root instanceof HTMLElement)) throw new Error('App root is required.');
  const router = createRouter(root, [
    { path: '/', access: 'HOME', page: statusPage('이동 중', '현재 사용자 상태를 확인하고 있습니다.') },
    { path: '/login', access: 'PUBLIC', page: loginPage },
    { path: '/signup', access: 'PUBLIC', page: signupPage },
    { path: '/invite/:token', access: 'PUBLIC', page: invitePage },
    { path: '/groups/select', access: 'AUTHENTICATED', page: groupSelectPage },
    { path: '/creature', access: 'GROUP_MEMBER', page: statusPage('크리처 홈 준비 완료', '공통 기반 검증을 마쳤습니다. 크리처 홈 업무 화면은 후속 기능에서 구현합니다.') },
    { path: '/admin', access: 'SUPER_ADMIN', page: statusPage('관리자 기반 준비 완료', '전체 관리자 업무 화면은 후속 기능에서 구현합니다.') },
    { path: '/403', access: 'PUBLIC', page: statusPage('접근할 수 없습니다', '현재 계정에 이 화면을 사용할 권한이 없습니다.') },
    { path: '*', access: 'PUBLIC', page: notFoundPage },
  ]);
  router.start();
}
