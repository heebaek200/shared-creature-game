export function createAppNavigation(currentPath) {
    const nav = document.createElement('nav');

    nav.innerHTML =
        `
    <div class="fs-6 px-2 mb-3 mt-2 fw-bold" style="color: var(--app-muted);">서비스 메뉴</div>
    <ul class="nav nav-pills flex-column mb-auto gap-1">
        <li class="nav-item">
            <a href="/creature" class="nav-link py-2 px-3" data-link>크리처</a>
        </li>
        <li class="nav-item">
            <a href="/quests" class="nav-link py-2 px-3" data-link>퀘스트</a>
        </li>
        <li class="nav-item">
            <a href="/rankings" class="nav-link py-2 px-3" data-link>랭킹</a>
        </li>
        <li class="nav-item">
            <a href="/group" class="nav-link py-2 px-3" data-link>그룹</a>
        </li>
        <li class="nav-item">
            <a href="/shop" class="nav-link py-2 px-3" data-link>상점</a>
        </li>
        <li class="nav-item">
            <a href="/avatar" class="nav-link py-2 px-3" data-link>아바타</a>
        </li>
        <li class="nav-item">
            <a href="/payments" class="nav-link py-2 px-3" data-link>결제 내역</a>
        </li>
        <li class="nav-item">
            <a href="/group-admin" class="nav-link py-2 px-3" data-link>그룹 관리</a>
        </li>
    </ul>
    `;

    nav.className =
        'd-none d-lg-flex flex-column flex-shrink-0 p-2 border-end';
    nav.style.cssText =
        'width: 230px; background-color: var(--app-surface); border-color: var(--app-border) !important;';

    // 현재 메뉴 활성화
    nav.querySelectorAll('a[data-link]').forEach((link) => {
        const isCurrent = link.getAttribute('href') === currentPath;

        link.classList.toggle('active', isCurrent);
        link.classList.toggle('text-white', isCurrent);
        link.style.backgroundColor = isCurrent ? 'var(--app-primary)' : '';
        link.style.color = isCurrent ? '' : 'var(--app-text)';

        if (isCurrent) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });

    return nav;
}
