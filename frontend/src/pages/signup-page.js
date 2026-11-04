// @ts-check
import {
    getSignupContext,
    completeSignup
} from '../features/auth/api.js';

/** 가입 대기 사용자의 닉네임 확정 Page를 생성합니다. */
export function signupPage({navigate}) {
    const element = document.createElement('main');
    element.className = 'app-shell auth-shell';

    element.innerHTML = `
<form class="app-card auth-card">
    <h1>프로필 완성</h1>
    
    <label class="form-label" for="provider">소셜 로그인</label>
    <input class="form-control" id="provider" readonly>
    
    <label class="form-label mt-3" for="email">이메일</label>
    <input class="form-control" id="email" readonly>
    
    <label class="form-label mt-3" for="nickname">닉네임</label>
    <input class="form-control" id="nickname"
            minlength="2" maxlength="20" required>
            
    <button class="btn btn-primary mt-3"
             type="submit" disabled>가입 완료</button>
        
    <p class="feedback mt-3" role="status">가입 정보를 조회하고 있습니다.</p>
</form>
`;
    const form = element.querySelector('form');
    const provider = element.querySelector('#provider');
    const email = element.querySelector('#email');
    const nickname = element.querySelector('#nickname');
    const submit = element.querySelector('button');
    const feedback = element.querySelector('.feedback');

    let disposed = false;

    /**
     * 가입 대기 정보를 조회하고 화면에 표시
     */
    async function loadSignupContext() {
        try {

            const data = await getSignupContext();
            if (disposed) {
                return;
            }

            provider.value = data.provider;
            email.value = data.email;
            nickname.value = data.suggestedNickname ?? '';

            submit.disabled = false;
            feedback.textContent = '';
        } catch (error) {

            if (disposed) {
                return;
            }

            feedback.textContent =
                error?.code === 'SIGNUP_CONTEXT_EXPIRED'
                    ? '가입 대기 시간이 만료되었습니다. 다시 로그인 해주세요.'
                    : '가입 정보를 불러오지 못했습니다.';
        }
    }


    const onSubmit = async (event) => {
      event.preventDefault();
      submit.disabled = true;

        try {
            await completeSignup(nickname.value);
           if (!disposed) navigate('/');
        } catch (error) {
            feedback.textContent =
                error.message;
          submit.disabled = false;
        }
    };
    form.addEventListener('submit', onSubmit);

    // Page가 생성되면 가입 정보를 한 번 조회합니다.
    void loadSignupContext();

    return {
      element,
      cleanup: () => {
        disposed = true;
        form.removeEventListener('submit', onSubmit)
      }
    };
}
