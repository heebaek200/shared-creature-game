import { createAppShell } from '../../components/app-shell.js';
import { adminRefundsPage } from '../admin-refunds-page.js';

/** 전체 관리자 환불 관리 화면을 공통 App Shell에 연결합니다. */
export function adminPage({ navigate, currentUser }) {
    const mounted = adminRefundsPage({ navigate });

    const element = createAppShell({
        content: mounted.element,
        currentPath: '/admin/refunds',
        currentUser,
        navigate,
    });

    return {
        element,
        cleanup: mounted.cleanup,
    };
}
