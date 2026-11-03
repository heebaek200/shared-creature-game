// @ts-check
import { createRouter } from './router.js';
import { loginPage } from '../pages/login-page.js';
import { signupPage } from '../pages/signup-page.js';
import { groupSelectPage } from '../pages/group-select-page.js';
import { notFoundPage } from '../pages/not-found-page.js';

/**
 * 공통 Route 표와 Page 생명주기를 시작합니다.
 * @param {Element|null} root 앱 Root Element
 */
export function startApplication(root) {
  if (!(root instanceof HTMLElement)) throw new Error('App root is required.');
  const router = createRouter(root, [
    { path: '/login', access: 'PUBLIC', page: loginPage },
    { path: '/signup', access: 'PUBLIC', page: signupPage },
    { path: '/groups/select', access: 'AUTHENTICATED', page: groupSelectPage },
    { path: '*', access: 'PUBLIC', page: notFoundPage },
  ]);
  router.start();
}
