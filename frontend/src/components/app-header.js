import { createThemeToggle } from './app-theme.js';
import titleIconLight from '../../assets/ui/header-title-icon-light.png';
import titleIconDark from '../../assets/ui/header-title-icon-dark.png';
import { logout } from '../features/auth/api.js';

export function createAppHeader({ currentUser = null, navigate, showGroupBadge = true } = {}) {
    const header = document.createElement('header');
    const isAuthenticated =
        currentUser?.authenticationState === 'AUTHENTICATED';
    header.innerHTML = `
        <div class="app-header__brand d-flex align-items-center gap-2">
            <span class="app-header__title fs-5 fw-bold d-flex align-items-center gap-2" style="color: var(--app-primary);">
              <img src="${titleIconLight}" alt="" aria-hidden="true" width="24" height="24" class="flex-shrink-0"/>
              <span class="app-header__title-text">다 같이 키우는 생명체</span>
            </span>
        </div>
        <div class="app-header__status d-flex align-items-center gap-3 flex-grow-1 ms-4"
             aria-label="현재 서비스 상태">
          <span data-header-season-info>로딩 중...</span>
        
          ${showGroupBadge ? `
            <span
                class="badge border px-2 py-2"
                style="background-color: var(--app-background); color: var(--app-text); border-color: var(--app-border) !important;"
                data-header-group-name>
                소속 그룹 없음
            </span>
          ` : ''}
        
          <span class="badge rounded-pill text-nowrap ms-auto me-2 px-3 py-2"
                style="background-color: var(--app-text); color: var(--app-surface);"
                data-header-connection-status aria-label="실시간 연결 상태">
            <span class="me-2" style="color: var(--app-primary);" aria-hidden="true">●</span>
            <span class="connection-status-label">실시간</span>
          </span>
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
    header.style.cssText =
        'background-color: var(--app-surface); border-color: var(--app-border) !important; height: 60px';

    return header;
}
