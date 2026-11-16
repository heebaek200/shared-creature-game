import { createAppShell } from '../components/app-shell.js';

export function adminPage({ navigate, currentUser }) {
    const content = document.createElement('div');

    const element = createAppShell({
        content,
        currentPath: location.pathname,
        currentUser,
        navigate,
    });

    return {
        element,
        cleanup: () => {},
    };
}