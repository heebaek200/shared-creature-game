
import { createAppShell } from '../../components/app-shell.js';
import { adminOperationsPage } from '../admin-operations-page.js';

/**
 * 전체 관리자 운영 관리 페이지를 공통 App Shell에 연결합니다.
 * 운영 경고 조회 및 주 관리자 재지정 기능을 표시합니다.
 * 화면 이동 시 자동 조회 타이머와 이벤트를 정리합니다.
 *
 * @param {{navigate: (path: string) => void, currentUser: object}} context
 * @returns {{element: HTMLElement, cleanup: () => void}} Router Page 객체
 */
export function adminPage({ navigate, currentUser }) {
    // 기존 운영 관리 페이지를 생성합니다.
    const mounted = adminOperationsPage();

    // 공통 관리자 레이아웃 안에 운영 관리 화면을 배치합니다.
    const element = createAppShell({
        content: mounted.element,
        currentPath: '/admin/operations',
        currentUser,
        navigate
    });

    // 기존 Page의 cleanup을 Router 생명주기에 연결합니다.
    return {
        element,
        cleanup: () => {
            mounted.cleanup();
        }
    };
}
