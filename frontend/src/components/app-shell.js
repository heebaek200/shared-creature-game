import {createAppHeader} from './app-header.js';
import { createAdminNavigation, createAppNavigation, createAppMobileNavigation, createAdminMobileNavigation } from './app-navigation.js';

import { getCurrentSeason } from '../features/season/api.js';
import { formatSeasonRemainingTime } from '../features/season/season-countdown.js';
import { getMyGroup } from '../features/group/api.js';

/**
 * 공통 Shell 과 페이지 콘텐츠 영역을 생성합니다.
 * @returns {{ shell: HTMLDivElement, body: HTMLDivElement }}
 */
export function createBaseLayout({ currentUser, navigate, showGroupBadge = true } = {}) {
    const shell = document.createElement('div');
    shell.className =
        'd-flex flex-column min-vh-100 app-shell app-shell-root w-100 m-0 p-0 overflow-x-hidden';

    const body = document.createElement('div');
    body.className = 'd-flex flex-grow-1 w-100 app-shell__body';

    const header = createAppHeader({ currentUser, navigate, showGroupBadge });
    shell.append(header, body);

    void getCurrentSeason()
        .then((response) => {
            if (!header.isConnected) return;

            const seasonData = response.data || response;
            const seasonInfo = header.querySelector('[data-header-season-info]');

            if (seasonInfo) {
                seasonInfo.textContent = formatSeasonRemainingTime(seasonData);
            }
        })
        .catch(() => {
            const seasonInfo = header.querySelector('[data-header-season-info]');

            if (seasonInfo) {
                seasonInfo.textContent = '시즌 정보 확인 불가';
            }
        });

    if (showGroupBadge && currentUser?.membership) {
        void getMyGroup()
            .then((response) => {
                if (!header.isConnected) return;

                const groupData = response.data || response;
                const groupName = groupData.group?.name;
                const groupNameElement = header.querySelector(
                    '[data-header-group-name]',
                );

                if (groupNameElement && groupName) {
                    groupNameElement.textContent = groupName;
                }
            })
            .catch(() => {
                // 그룹 정보 조회에 실패해도 페이지와 공통 Header는 그대로 표시.
            });
    }

    return {shell, body};
}

/**
 * Header와 페이지 콘텐츠만 사용하는 레이아웃입니다.
 * @param {{ content: HTMLElement }} options
 * @returns {HTMLElement}
 */
export function createHeaderLayout({content, currentUser, navigate, showGroupBadge = true }) {
    const { shell, body } = createBaseLayout({ currentUser, navigate, showGroupBadge });
    body.append(content);
    return shell;
}

/**
 * Header, 사이드 메뉴, 본문을 사용하는 서비스 레이아웃입니다.
 * @param {{ content: HTMLElement, currentPath: string }} options
 * @returns {HTMLElement}
 */
export function createAppShell({content, currentPath, currentUser, navigate, showGroupBadge }) {
    const shouldShowGroupBadge = showGroupBadge ?? !currentUser?.roles?.includes('SUPER_ADMIN');
    const { shell, body } = createBaseLayout({ currentUser, navigate, showGroupBadge: shouldShowGroupBadge });
    const pageLayout = document.createElement('div');
    pageLayout.className = 'flex-grow-1 d-flex flex-column min-w-0';

    const pageContainer = document.createElement('div');
    pageContainer.className =
        'container-fluid px-5 pt-4 pb-4 d-flex flex-column flex-grow-1';

    pageContainer.append(content);
    pageLayout.append(pageContainer);

    const isSuperAdmin = currentUser?.roles?.includes('SUPER_ADMIN');

    const navigation = isSuperAdmin
        ? createAdminNavigation(currentPath)
        : createAppNavigation(currentPath, currentUser);

    const mobileNavigation = isSuperAdmin
        ? createAdminMobileNavigation(currentPath)
        : createAppMobileNavigation(currentPath, currentUser);

    body.append(navigation, pageLayout);
    shell.append(mobileNavigation);

    return shell;
}
