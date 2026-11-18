// @ts-check
import { getCurrentUser } from '../features/auth/api.js';
import { statusPage } from '../pages/status-page.js';

/** @typedef {{ path: string, access: string, page: (context: {navigate: (path: string) => void, currentUser: object | null}) => {element: HTMLElement, cleanup?: () => void} }} Route */

/**
 * History API 이동과 Page mount·cleanup을 관리합니다.
 * @param {HTMLElement} root 앱 Root Element
 * @param {Route[]} routes Route 표
 */
export function createRouter(root, routes) {
  let cleanup = () => {};
  let renderVersion = 0;

  async function render() {
    const currentRenderVersion = ++renderVersion;

    cleanup();

    const route = routes.find((candidate) => {
      if (candidate.path === '/invite/:token') {
        return /^\/invite\/[^/]+$/.test(location.pathname);
      }
      return candidate.path === location.pathname;
    }) ?? routes.find((candidate) => candidate.path === '*');

    // 회원가입은 PUBLIC이어도 가입 대기 상태 확인이 필요.
    const requiresUserInfo =
        route.access !== 'PUBLIC' ||
        route.path === '/signup';

    let me = null;

    try {
      me = await getCurrentUser();
      if (currentRenderVersion !== renderVersion) return;
    } catch (error) {
      if (currentRenderVersion !== renderVersion) return;

      if (requiresUserInfo) {
        console.error('사용자 정보 조회 실패:', error);

        const errorPage = statusPage(
            '일시적인 오류가 발생했습니다',
            '사용자 정보를 확인하지 못했습니다. 잠시 후 페이지를 새로고침해 주세요.'
        );

        const mounted = errorPage({ navigate });
        root.replaceChildren(mounted.element);
        cleanup = mounted.cleanup ?? (() => {});
        return;
      }

      // 공개 화면은 사용자 정보 조회에 실패해도 표시.
      console.warn('공개 화면의 사용자 정보 조회 실패:', error);
    }


    if (route.access === 'HOME') {
      replace(defaultRoute(me));
      return;
    }

    // 회원가입 화면 접근 검사
    if (route.path === '/signup') {
      const canSignup =
          me.authenticationState === 'SIGNUP_PENDING' &&
          me.onboardingState === 'SIGNUP_REQUIRED';

      if (!canSignup) {
        replace(defaultRoute(me));
        return;
      }
    }

    // 보호된 페이지 접근 검사
    if (route.access !== 'PUBLIC') {
      if (me.authenticationState !== 'AUTHENTICATED') {
        replace(
            me.onboardingState === 'SIGNUP_REQUIRED'
                ? '/signup'
                : '/login'
        );
        return;
      }

      if (route.access === 'GROUP_MEMBER' && !me.membership) {
        replace('/groups/select');
        return;
      }

      // 그룹 관리 화면은 DB에서 조회된 현재 멤버십 역할로 검사합니다.
      if (route.access === 'GROUP_ADMIN') {
        const groupRole = me.membership?.role;

        const canManageGroup =
            groupRole === 'PRIMARY' || groupRole === 'DEPUTY';

        if (!canManageGroup) {
          replace('/403');
          return;
        }
      }

      if (
          route.access === 'SUPER_ADMIN' &&
          !me.roles.includes('SUPER_ADMIN')
      ) {
        replace('/403');
        return;
      }

      if (route.path === '/groups/select' && me.membership) {
        replace('/creature');
        return;
      }
    }

    const mounted = route.page({
      navigate,
      currentUser: me,
    });
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
