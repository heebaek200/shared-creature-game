// @ts-check

/** 존재하지 않는 CSR Route 안내 Page를 생성합니다. */
export function notFoundPage() {
  const element = document.createElement('main');
  element.className = 'app-shell auth-shell';
  element.innerHTML = `<section class="app-card auth-card"><p class="eyebrow">404</p><h1>페이지를 찾을 수 없습니다</h1><a class="btn btn-primary" href="/login" data-link>로그인으로 이동</a></section>`;
  return { element };
}
