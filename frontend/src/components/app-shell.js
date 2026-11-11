import { createAppHeader } from './app-header.js';
import { createAppNavigation } from './app-navigation.js';

/**
 * Header, 좌측 Navigation, 페이지 콘텐츠를 조립합니다.
 * @param {{ content: HTMLElement, currentPath: string }} options
 */
export function createAppShell({ content, currentPath }) {
    const shell = document.createElement('main');
    shell.className =
        'd-flex flex-column min-vh-100 app-shell w-100 m-0 p-0 overflow-x-hidden';
    shell.style.backgroundColor = 'var(--app-background)';
    shell.style.color = 'var(--app-text)';
    shell.style.padding = '0';
    shell.style.margin = '0';
    shell.style.maxWidth = 'none';

    const body = document.createElement('div');
    body.className = 'd-flex flex-grow-1 w-100';
    body.style.paddingTop = '60px';

    const pageLayout = document.createElement('div');
    pageLayout.className = 'flex-grow-1 d-flex flex-column min-w-0';

    const pageContainer = document.createElement('div');
    pageContainer.className =
        'container-fluid px-5 pt-4 pb-4 d-flex flex-column flex-grow-1';

    pageContainer.append(content);
    pageLayout.append(pageContainer);
    body.append(createAppNavigation(currentPath), pageLayout);
    shell.append(createAppHeader(), body);

    return shell;
}