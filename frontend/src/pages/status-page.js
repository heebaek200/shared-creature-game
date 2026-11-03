// @ts-check

/** 후속 업무 화면이 구현되기 전 상태와 다음 개발 범위를 안내합니다. */
export function statusPage(title, description) {
  return () => {
    const element = document.createElement('main');
    element.className = 'app-shell';
    element.innerHTML = `<section class="app-card"><p class="eyebrow">공통 기반</p><h1></h1><p></p></section>`;
    element.querySelector('h1').textContent = title;
    element.querySelector('section > p:last-child').textContent = description;
    return { element };
  };
}
