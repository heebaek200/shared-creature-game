// 페이지별 등록 없이 좁은 화면의 키보드 포커스 이동을 보완합니다.
let initialized = false;

export function initializeKeyboardFocusScroll() {
    if (initialized) return;
    initialized = true;

    const narrowScreen = window.matchMedia('(max-width: 1032px)');
    let tabNavigation = false;
    let resetTimer;
    let frame;

    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) {
            return;
        }

        tabNavigation = true;
        clearTimeout(resetTimer);
        resetTimer = setTimeout(() => { tabNavigation = false; }, 0);
    }, true);

    document.addEventListener('pointerdown', () => {
        tabNavigation = false;
        cancelAnimationFrame(frame);
    }, true);

    document.addEventListener('focusin', (event) => {
        if (!tabNavigation || !narrowScreen.matches) return;
        tabNavigation = false;

        const target = event.target;
        if (!(target instanceof HTMLElement)) return;

        // 모달과 고정 메뉴는 자체 포커스 처리를 유지합니다.
        if (target.closest('.modal, [role="dialog"], .app-header, .mobile-navigation')) {
            return;
        }

        cancelAnimationFrame(frame);
        frame = requestAnimationFrame(() => {
            if (!target.isConnected || document.activeElement !== target) return;

            const viewport = window.visualViewport;
            const viewportTop = viewport?.offsetTop ?? 0;
            const viewportBottom = viewportTop + (viewport?.height ?? window.innerHeight);
            const margin = 12;
            let top = viewportTop + margin;
            let bottom = viewportBottom - margin;

            for (const overlay of document.querySelectorAll('.app-header, .mobile-navigation')) {
                const style = getComputedStyle(overlay);
                const rect = overlay.getBoundingClientRect();
                if (style.display === 'none' || rect.height === 0 ||
                    !['fixed', 'sticky'].includes(style.position)) continue;

                if (overlay.matches('.app-header') && rect.top <= top) {
                    top = Math.max(top, rect.bottom + margin);
                } else if (overlay.matches('.mobile-navigation') && rect.bottom >= bottom) {
                    bottom = Math.min(bottom, rect.top - margin);
                }
            }

            if (bottom <= top) return;

            const rect = target.getBoundingClientRect();
            const delta = rect.height > bottom - top || rect.top < top
                ? rect.top - top
                : rect.bottom > bottom ? rect.bottom - bottom : 0;

            if (delta !== 0) {
                // 연속 Tab 입력에도 애니메이션 지연 없이 포커스를 따라갑니다.
                window.scrollBy({ top: delta, behavior: 'instant' });
            }
        });
    });
}
