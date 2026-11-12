/*
 * 기본 메뉴 (일반 유저 + 그룹 관리자)
 */
export function createAppNavigation(currentPath, currentUser = null) {
    const nav = document.createElement('nav');
    const roles = Array.isArray(currentUser?.roles) ? currentUser.roles : [];
    const canManageGroup =
        roles.includes('PRIMARY') || roles.includes('DEPUTY');

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
        ${canManageGroup ? `
        <li class="nav-item">
            <a href="/group-admin" class="nav-link py-2 px-3" data-link>그룹 관리</a>
        </li>
        ` : ''}
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

/*
 * 관리자 메뉴 (전체 관리자)
 */
export function createAdminNavigation(currentPath) {
    const nav = document.createElement('nav');

    nav.innerHTML = `
        <div class="fs-6 px-2 mb-3 mt-2 fw-bold"
             style="color: var(--app-muted);">
            관리자 메뉴
        </div>

        <ul class="nav nav-pills flex-column mb-auto gap-1">
            <li class="nav-item">
                <a href="/admin" class="nav-link py-2 px-3" data-link>현황</a>
            </li>
            <li class="nav-item">
                <a href="/admin/reports" class="nav-link py-2 px-3" data-link>신고</a>
            </li>
            <li class="nav-item">
                <a href="/admin/refunds" class="nav-link py-2 px-3" data-link>환불</a>
            </li>
            <li class="nav-item">
                <a href="/admin/operations" class="nav-link py-2 px-3" data-link>운영</a>
            </li>
        </ul>
    `;

    nav.className =
        'd-none d-lg-flex flex-column flex-shrink-0 p-2 border-end';
    nav.style.cssText =
        'width: 230px; background-color: var(--app-surface); border-color: var(--app-border) !important;';

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


/*
 * 메뉴 모바일
 */
function createMobileNavigation(currentPath, label, items) {
    const nav = document.createElement('nav');
    nav.className = 'mobile-navigation';
    nav.setAttribute('aria-label', label);

    nav.innerHTML = items.map(({ href, text }) => `
        <a
            href="${href}"
            class="mobile-navigation__link${href === currentPath ? ' active' : ''}"
            data-link
            ${href === currentPath ? 'aria-current="page"' : ''}
        >${text}</a>
    `).join('');

    return nav;
}

export function createAppMobileNavigation(currentPath, currentUser = null) {
    const roles = Array.isArray(currentUser?.roles) ? currentUser.roles : [];
    const canManageGroup =
        roles.includes('PRIMARY') || roles.includes('DEPUTY');

    const morePaths = ['/shop', '/avatar', '/payments'];
    if (canManageGroup) morePaths.push('/group-admin');

    const moreIsActive = morePaths.includes(currentPath);

    const nav = document.createElement('nav');
    nav.className = 'mobile-navigation';
    nav.setAttribute('aria-label', '모바일 서비스 메뉴');

    nav.innerHTML = `
        <a href="/creature" class="mobile-navigation__link" data-link>크리처</a>
        <a href="/quests" class="mobile-navigation__link" data-link>퀘스트</a>
        <a href="/rankings" class="mobile-navigation__link" data-link>랭킹</a>
        <a href="/group" class="mobile-navigation__link" data-link>그룹</a>

        <details class="mobile-navigation__more" ${moreIsActive ? 'data-active="true"' : ''}>
            <summary class="mobile-navigation__link ${moreIsActive ? 'active' : ''}">
                더보기
            </summary>
            <div class="mobile-navigation__menu">
                <a href="/shop" data-link>상점</a>
                <a href="/avatar" data-link>아바타</a>
                <a href="/payments" data-link>결제 내역</a>
                ${canManageGroup
        ? '<a href="/group-admin" data-link>그룹 관리</a>'
        : ''}
            </div>
        </details>
    `;

    nav.querySelectorAll('a[data-link]').forEach((link) => {
        const isCurrent = link.getAttribute('href') === currentPath;

        link.classList.toggle('active', isCurrent);

        if (isCurrent) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });

    return nav;
}

export function createAdminMobileNavigation(currentPath) {
    const items = [
        { href: '/admin', text: '현황' },
        { href: '/admin/reports', text: '신고' },
        { href: '/admin/refunds', text: '환불' },
        { href: '/admin/operations', text: '운영' },
    ];

    return createMobileNavigation(currentPath, '모바일 관리자 메뉴', items);
}