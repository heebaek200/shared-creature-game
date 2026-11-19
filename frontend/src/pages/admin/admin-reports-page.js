
import { createAppShell } from '../../components/app-shell.js';
import { adminReportsPage } from '../admin-reports-page.js';

/**
 * 전체 관리자 신고 관리 페이지를 공통 App Shell에 연결합니다.
 * 신고 목록·상세·기각·강퇴 화면을 본문에 배치합니다.
 * Router 화면 종료 시 해당 페이지의 이벤트를 정리합니다.
 *
 * @param {{navigate: (path: string) => void, currentUser: object}} context
 * @returns {{element: HTMLElement, cleanup: () => void}} Router Page 객체
 */
export function adminPage({ navigate, currentUser }) {
    // 신고 관리 화면을 생성합니다.
    const mounted = adminReportsPage();

    // 공통 Header 및 관리자 내비게이션을 적용합니다.
    const element = createAppShell({
        content: mounted.element,
        currentPath: '/admin/reports',
        currentUser,
        navigate
    });

    // 신고 처리 화면의 생명주기를 Router에 전달합니다.
    return {
        element,
        cleanup: () => {
            mounted.cleanup();
        }
    };
}
