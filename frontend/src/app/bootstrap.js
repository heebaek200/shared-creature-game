// @ts-check
import { createRouter } from './router.js';
import { loginPage } from '../pages/login-page.js';
import { signupPage } from '../pages/signup-page.js';
import { groupSelectPage } from '../pages/group-select-page.js';
import { notFoundPage } from '../pages/not-found-page.js';
import { statusPage } from '../pages/status-page.js';
import { creaturePage } from '../pages/creature-page.js';
import { invitePage } from '../pages/invite-page.js';
import { adminPage as questsPage } from '../pages/quests-page.js';
import { adminPage as rankingsPage } from '../pages/rankings-page.js';
import { adminPage as groupPage } from '../pages/group-page.js';
import { adminPage as shopPage } from '../pages/shop-page.js';
import { adminPage as avatarPage } from '../pages/avatar-page.js';
import { adminPage as paymentsPage } from '../pages/payments-page.js';
import { adminPage as groupAdminPage } from '../pages/group-admin-page.js';

import { adminPage as adminOperationsPage } from '../pages/admin/admin-operations-page.js';
import { adminPage as adminReportsPage } from '../pages/admin/admin-reports-page.js';
import { adminPage as adminRefundsPage } from '../pages/admin/admin-refund-page.js';
import {adminPage} from "../pages/admin/admin-page.js";

/**
 * 공통 Route 표와 Page 생명주기를 시작합니다.
 * 기존 로그인·회원가입·그룹 선택 경로를 유지합니다.
 * 초대 페이지와 실제 크리처 화면을 함께 등록합니다.
 * @param {Element|null} root 앱 Root Element
 */
export function startApplication(root) {
  if (!(root instanceof HTMLElement)) {
    throw new Error('App root is required.');
  }

  // 각 URL에 대응하는 화면과 접근 권한을 등록합니다.
  const router = createRouter(root, [
    { path: '/', access: 'HOME', page: statusPage('이동 중', '현재 사용자 상태를 확인하고 있습니다.') },
    { path: '/login', access: 'PUBLIC', page: loginPage },
    { path: '/signup', access: 'PUBLIC', page: signupPage },
    { path: '/invite/:token', access: 'PUBLIC', page: invitePage },
    { path: '/groups/select', access: 'AUTHENTICATED', page: groupSelectPage },
    { path: '/creature', access: 'GROUP_MEMBER', page: creaturePage },
    { path: '/quests', access: 'GROUP_MEMBER', page: questsPage },
    { path: '/rankings', access: 'GROUP_MEMBER', page: rankingsPage },
    { path: '/group', access: 'GROUP_MEMBER', page: groupPage },
    { path: '/shop', access: 'AUTHENTICATED', page: shopPage },
    { path: '/avatar', access: 'AUTHENTICATED', page: avatarPage },
    { path: '/payments', access: 'AUTHENTICATED', page: paymentsPage },
    { path: '/group-admin', access: 'GROUP_ADMIN', page: groupAdminPage },
    { path: '/admin', access: 'SUPER_ADMIN', page: adminPage },
    { path: '/admin/reports', access: 'SUPER_ADMIN', page: adminReportsPage },
    { path: '/admin/refunds', access: 'SUPER_ADMIN', page: adminRefundsPage },
    { path: '/admin/operations', access: 'SUPER_ADMIN', page: adminOperationsPage },
    { path: '/403', access: 'PUBLIC', page: statusPage('접근할 수 없습니다', '현재 계정에 이 화면을 사용할 권한이 없습니다.') },
    { path: '*', access: 'PUBLIC', page: notFoundPage },
  ]);

  // 등록한 라우트를 이용해 애플리케이션을 시작합니다.
  router.start();
}