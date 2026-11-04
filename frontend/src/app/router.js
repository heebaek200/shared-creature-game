// @ts-check
import { getCurrentUser } from '../features/auth/api.js';

/** @typedef {{ path: string, access: string, page: (context: {navigate: (path: string) => void}) => {element: HTMLElement, cleanup?: () => void} }} Route */

/**
 * History API 이동과 Page mount·cleanup을 관리합니다.
 * @param {HTMLElement} root 앱 Root Element
 * @param {Route[]} routes Route 표
 */
export function createRouter(root, routes) {
  let cleanup = () => {};

  async function render() {
    cleanup();

    /**
     * 정확한 문자열 비교가 아닌 정규식으로 경로를 확인하도록 수정
     */
    const route = routes.find((candidate) => {
      if (candidate.path === '/invite/:token') {
        return /^\/invite\/[^/]+$/.test(location.pathname);
      }
      return candidate.path === location.pathname;
    }) ?? routes.find((candidate) => candidate.path === '*');

    if (route.access === 'HOME') {
      const me = await getCurrentUser();
      replace(defaultRoute(me));
      return;
    }

    // 회원가입 화면에 접근할 수 있는지 검사합니다.
    if (route.path === '/signup') {
      const me = await getCurrentUser();

      const canSignup =
          me.authenticationState === 'SIGNUP_PENDING' &&
          me.onboardingState === 'SIGNUP_REQUIRED';

      if (!canSignup) {
        replace(defaultRoute(me));
        return;
      }
    }

    if (route.access !== 'PUBLIC') {
      const me = await getCurrentUser();
      if (me.authenticationState !== 'AUTHENTICATED') {
        replace(me.onboardingState === 'SIGNUP_REQUIRED' ? '/signup' : '/login');
        return;
      }
      if (route.access === 'GROUP_MEMBER' && !me.membership) {
        replace('/groups/select');
        return;
      }
      if (route.access === 'SUPER_ADMIN' && !me.roles.includes('SUPER_ADMIN')) {
        replace('/403');
        return;
      }
      if (route.path === '/groups/select' && me.membership) {
        replace('/creature');
        return;
      }
    }

    const mounted = route.page({ navigate });
    root.replaceChildren(mounted.element);
    cleanup = mounted.cleanup ?? (() => {});
  }

  function navigate(path) {
    history.pushState({}, '', path);
    void render();
  }

  function replace(path) {
    history.replaceState({}, '', path);
    void render();
  }

  function onClick(event) {
    const target = event.target instanceof Element ? event.target.closest('[data-link]') : null;
    if (!(target instanceof HTMLAnchorElement)) return;
    event.preventDefault();
    navigate(target.pathname);
  }

  return {
    start() {
      window.addEventListener('popstate', render);
      document.addEventListener('click', onClick);
      void render();
    },
    navigate,
  };
}

/**
 * 현재 사용자 상태의 기본 CSR 진입 경로를 결정합니다.
 */
function defaultRoute(me) {
  if (me.authenticationState === 'SIGNUP_PENDING' &&
      me.onboardingState === 'SIGNUP_REQUIRED') {
    return '/signup';
  }
  if (me.authenticationState !== 'AUTHENTICATED') return '/login';
  if (me.roles.includes('SUPER_ADMIN')) return '/admin';
  if (me.membership) return '/creature';
  return '/groups/select';
}
