// @ts-check
import { createOAuthAttempt } from '../features/auth/api.js';

/** Google 로그인 진입 Page를 생성합니다. */
export function loginPage() {
  const element = document.createElement('main');
  element.className = 'app-shell auth-shell';
  element.innerHTML = `<section class="app-card auth-card"><p class="eyebrow">함께 만드는 작은 변화</p><h1>다 같이 키우는 생명체</h1><p>소셜 계정으로 시작해 공동 크리처를 키워 보세요.</p><button class="btn btn-primary" type="button">Google로 계속하기</button><p class="feedback" role="status"></p></section>`;
  const button = element.querySelector('button');
  const feedback = element.querySelector('.feedback');
  const onClick = async () => {
    button.disabled = true;
    try {
      const result = await createOAuthAttempt();
      location.assign(result.authorizationUrl);
    } catch (error) {
      feedback.textContent = error.message;
      button.disabled = false;
    }
  };
  button.addEventListener('click', onClick);
  return { element, cleanup: () => button.removeEventListener('click', onClick) };
}
