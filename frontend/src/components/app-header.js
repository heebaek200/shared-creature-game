import { createThemeToggle } from './app-theme.js';
import titleIconLight from '../../assets/ui/header-title-icon-light.png';
import titleIconDark from '../../assets/ui/header-title-icon-dark.png';
import { logout } from '../features/auth/api.js';
import {stopSeasonTracker} from '../features/season/season-tracker.js';

export function createAppHeader({ currentUser = null, navigate, showGroupBadge = true, showSeasonInfo = true, showConnectionStatus = true } = {}) {
    const header = document.createElement('header');
    const isAuthenticated =
        currentUser?.authenticationState === 'AUTHENTICATED';
    header.innerHTML = `
        <div class="app-header__brand d-flex align-items-center gap-2">
            <span class="app-header__title fs-5 fw-bold d-flex align-items-center gap-2">
              <img src="${titleIconLight}" alt="" aria-hidden="true" width="24" height="24" class="app-header__title-icon app-header__title-icon--light flex-shrink-0"/>
              <img src="${titleIconDark}" alt="" aria-hidden="true" width="24" height="24" class="app-header__title-icon app-header__title-icon--dark flex-shrink-0"/>
              <span class="app-header__title-text">다 같이 키우는 생명체</span>
            </span>
        </div>
        <div class="app-header__status d-flex align-items-center gap-3 flex-grow-1 ms-4" aria-label="현재 서비스 상태">
          ${showSeasonInfo ? `<span data-header-season-info>로딩 중...</span>` : ''}
        
          ${showGroupBadge ? `
            <span
                class="badge border px-2 py-2 app-header__group-badge"
                data-header-group-name>
                소속 그룹 없음
            </span>
          ` : ''}
        
          ${showConnectionStatus ? `<span class="badge rounded-pill text-nowrap ms-auto me-2 px-3 py-2 app-header__connection-status"
            data-header-connection-status data-connection-state="pending" role="status" aria-live="polite" aria-atomic="true"
            aria-label="실시간 연결 상태: 연결 확인 중" title="실시간 연결 상태: 연결 확인 중">
            <span class="me-2 app-header__connection-dot" aria-hidden="true">●</span>
            <span class="connection-status-label">연결 확인 중</span>
          </span>` : ''}
        </div>        
        <div class="app-header__actions d-flex align-items-center gap-2" data-header-actions>
            ${isAuthenticated
                ? `<button
                  type="button"
                  class="btn btn-sm btn-outline-secondary px-3 text-nowrap flex-shrink-0"
                  data-header-logout
               >로그아웃</button>`
                : `<a
                  href="/login"
                  class="btn btn-sm btn-outline-secondary px-3 text-nowrap flex-shrink-0"
                  data-link
               >로그인</a>`
            }
            <span class="small text-danger"
                data-header-auth-feedback
                role="status" aria-live="polite">      
            </span>
        </div>
    `;

    const logoutButton = header.querySelector('[data-header-logout]');
    const logoutFeedback = header.querySelector('[data-header-auth-feedback]');

    logoutButton?.addEventListener('click', async () => {
        logoutButton.disabled = true;
        if (logoutFeedback) logoutFeedback.textContent = '';

        try {
            await logout();
            stopSeasonTracker();
            navigate('/login');
        } catch {
            if (logoutFeedback) {
                logoutFeedback.textContent = '로그아웃에 실패했습니다. 다시 시도해 주세요.';
            }
            logoutButton.disabled = false;
        }
    });

    header.querySelector('[data-header-actions]')
        .prepend(createThemeToggle());
    header.className =
        'app-header position-fixed top-0 start-0 w-100 border-bottom px-4 py-3 d-flex justify-content-between align-items-center z-3';
    return header;
}

export function updateHeaderConnectionStatus(root, status) {
    const badge = root.querySelector('[data-header-connection-status]');

    if (!badge) return;

    const states = {
        connecting: {
            label: '실시간 연결 중',
            state: 'pending',
        },
        live: {
            label: '실시간 연결됨',
            state: 'connected',
        },
        polling: {
            label: '30초 동기화 중',
            state: 'polling',
        },
        error: {
            label: '연결 복구 중',
            state: 'error',
        },
    };

    const current = states[status] ?? {
        label: '연결 상태 확인',
        state: 'disconnected',
    };

    const label = badge.querySelector('.connection-status-label');

    if (label) {
        label.textContent = current.label;
    }

    badge.dataset.connectionState = current.state;

    const description = `실시간 연결 상태: ${current.label}`;
    badge.setAttribute('aria-label', description);
    badge.setAttribute('title', description);
}
