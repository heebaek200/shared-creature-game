
// @ts-check
import { createOAuthAttempt } from '../features/auth/api.js';
import {createHeaderLayout} from "../components/app-shell.js";

/**
 * Google 로그인 진입 Page를 생성합니다.
 */
export function loginPage({ currentUser, navigate }) {
  const content = document.createElement('main');
  content.className = 'app-shell min-vh-100 bg-body-tertiary';

  content.innerHTML = `
    <div class="container py-4 py-md-5">
        <!-- 서비스 소개 영역 -->
        <section class="card border-0 shadow-sm rounded-4 mb-4">
            <div class="card-body p-3 p-md-4">
                <div class="bg-body-secondary rounded-3 d-flex flex-column align-items-center justify-content-center text-center py-5 mb-4"
                     style="min-height: 180px;">
                    <div class="display-3 mb-2" aria-hidden="true"></div>
                    <p class="text-success fw-semibold mb-1">
                        공동 크리처 얼리스트
                    </p>
                    <p class="text-secondary small mb-0">
                        자연 · 성장 · 협력
                    </p>
                </div>

                <h2 class="h5 fw-bold mb-3">
                    함께 키우는 작은 일상
                </h2>
                <p class="text-body-secondary mb-2">
                    공개 그룹에서 일상 퀘스트를 수행하고
                    하나의 크리처를 함께 성장시킵니다.
                </p>
            </div>
        </section>

        <!-- 로그인 영역 -->
        <section class="card border-0 shadow-sm rounded-4">
            <div class="card-body p-4 p-md-5">

                <h1 class="h3 fw-bold mb-2">
                    다 같이 키우는 생명체
                </h1>

                <p class="text-secondary mb-4">
                    소셜 계정으로 시작하세요.
                </p>

                <button
                    id="google-login-button"
                    class="btn btn-primary w-100 py-2 mb-3"
                    type="button">
                    Google로 계속하기
                </button>

                <!-- 로그인 오류 안내 -->
                <div
                    class="alert alert-danger mb-3 d-none login-error"
                    role="alert"
                    aria-live="polite">
                    <p class="feedback small mb-0"></p>
                </div>

                <p class="text-secondary small mb-0">
                    
                </p>

            </div>
        </section>

    </div>
    `;

  const element = createHeaderLayout({ content, currentUser, navigate, showGroupBadge: false });

  const button = element.querySelector('#google-login-button');
  const feedback = element.querySelector('.feedback');
  const loginError = element.querySelector('.login-error');

  if (!button || !feedback || !loginError) {
    throw new Error('로그인 화면 요소를 찾을 수 없습니다.');
  }
  // Google OAuth 콜백 실패 사유 안내
  const params = new URLSearchParams(location.search);
  const oauthError = params.get('oauthError');

  const oauthErrorMessages = {
    OAUTH_ACCESS_DENIED:
        'Google 로그인이 취소되었습니다. 다시 로그인해 주세요.',
    OAUTH_INVALID_STATE:
        '로그인 요청을 확인할 수 없습니다. 다시 로그인해 주세요.',
    OAUTH_SESSION_EXPIRED:
        '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.',
  };

  if (oauthError) {
    feedback.textContent =
        oauthErrorMessages[oauthError] ||
        'Google 인증에 실패했습니다. 다시 로그인해 주세요.';

    loginError.classList.remove('d-none');
  }

  // Google 로그인
  const onClick = async () => {
    button.disabled = true;
    button.textContent = '로그인 연결 중...';
    feedback.textContent = '';
    loginError.classList.add('d-none');


    try {
      // 초대 로그인 여부를 확인합니다.
      const params = new URLSearchParams(location.search);

      const returnRoute =
          params.get('returnRoute') === 'INVITE'
              ? 'INVITE'
              : 'DEFAULT';

      const result = await createOAuthAttempt(returnRoute);
      location.assign(result.authorizationUrl);
    } catch (error) {
      feedback.textContent =
          error instanceof Error
              ? error.message
              : '로그인 중 오류가 발생했습니다.';

      loginError.classList.remove('d-none');

      button.disabled = false;
      button.textContent = 'Google로 계속하기';
    }
  };

  button.addEventListener('click', onClick);

  return {
    element,
    cleanup: () => {
      button.removeEventListener('click', onClick);
    },
  };
}
