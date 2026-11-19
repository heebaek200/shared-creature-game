
import { createAppShell } from '../../components/app-shell.js';
import { adminDashboardPage } from '../admin-dashboard-page.js';

/**
 * 전체 관리자 대시보드를 공통 App Shell에 연결합니다.
 * 대시보드의 element를 본문에 배치합니다.
 * Router가 화면을 종료하면 대시보드의 cleanup도 호출합니다.
 *
 * @param {{navigate: (path: string) => void, currentUser: object}} context
 * @returns {{element: HTMLElement, cleanup: () => void}} Router Page 객체
 */
export function adminPage({ navigate, currentUser }) {
    // 대시보드 페이지를 생성합니다.
    const mounted = adminDashboardPage();

    // 공통 레이아웃 내부에 대시보드를 배치합니다.
    const element = createAppShell({
        content: mounted.element,
        currentPath: '/admin',
        currentUser,
        navigate
    });

    // Router의 Page 생명주기와 기존 페이지를 연결합니다.
    return {
        element,
        cleanup: () => {
            mounted.cleanup();
        }
    };
}
