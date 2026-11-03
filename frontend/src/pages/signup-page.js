// @ts-check
import { completeSignup } from '../features/auth/api.js';

/** 가입 대기 사용자의 닉네임 확정 Page를 생성합니다. */
export function signupPage({ navigate }) {
  const element = document.createElement('main');
  element.className = 'app-shell auth-shell';
  element.innerHTML = `<form class="app-card auth-card"><h1>프로필 완성</h1><label class="form-label" for="nickname">닉네임</label><input class="form-control" id="nickname" minlength="2" maxlength="20" required><button class="btn btn-primary mt-3" type="submit">가입 완료</button><p class="feedback" role="status"></p></form>`;
  const form = element.querySelector('form');
  const feedback = element.querySelector('.feedback');
  const onSubmit = async (event) => {
    event.preventDefault();
    try {
      const nickname = element.querySelector('#nickname').value;
      await completeSignup(nickname);
      navigate('/groups/select');
    } catch (error) {
      feedback.textContent = error.message;
    }
  };
  form.addEventListener('submit', onSubmit);
  return { element, cleanup: () => form.removeEventListener('submit', onSubmit) };
}
