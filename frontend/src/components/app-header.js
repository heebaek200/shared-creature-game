import { createThemeToggle } from './app-theme.js';
import titleIconLight from '../../assets/ui/header-title-icon-light.png';
import titleIconDark from '../../assets/ui/header-title-icon-dark.png';

export function createAppHeader() {
    const header = document.createElement('header');

    header.innerHTML = `
        <div class="d-flex align-items-center gap-2">
            <span class="fs-5 fw-bold d-flex align-items-center gap-2" style="color: var(--app-primary);">
              <img src="${titleIconLight}" alt="" aria-hidden="true" width="24" height="24" class="flex-shrink-0"/>
              다 같이 키우는 생명체
            </span>
        </div>
        <div class="d-flex align-items-center gap-3 flex-grow-1 ms-4"
             aria-label="현재 서비스 상태">
          <span data-header-season-info>로딩 중...</span>
        
          <span class="badge border px-2 py-2"
                style="background-color: var(--app-background); color: var(--app-text); border-color: var(--app-border) !important;"
                data-header-group-name>
            소속 그룹 없음
          </span>
        
          <span class="badge rounded-pill text-nowrap ms-auto me-2 px-3 py-2"
                style="background-color: var(--app-text); color: var(--app-surface);"
                data-header-connection-status >
            <span class="me-1 " style="color: var(--app-primary);" aria-hidden="true">●</span>
            실시간
          </span>
        </div>        
        <div class="d-flex align-items-center gap-2" data-header-actions>
            <button type="button" class="btn btn-sm btn-outline-secondary px-3 text-nowrap flex-shrink-0">로그아웃</button>
        </div>
    `;

    header.querySelector('[data-header-actions]')
        .prepend(createThemeToggle());
    header.className =
        'position-fixed top-0 start-0 w-100 border-bottom px-4 py-3 d-flex justify-content-between align-items-center z-3';
    header.style.cssText =
        'background-color: var(--app-surface); border-color: var(--app-border) !important; height: 60px';

    return header;
}
