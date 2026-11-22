// @ts-check
import { createOAuthAttempt } from '../features/auth/api.js';
import { createHeaderLayout } from '../components/app-shell.js';

/**
 * Google 및 Naver 소셜 로그인 화면을 생성합니다.
 * main 브랜치의 공통 Header를 사용하고, 공급자별 OAuth 로그인을 연결합니다.
 * @param {{ currentUser: any, navigate: Function }} options 페이지 생성 옵션
 * @returns {{ element: HTMLElement, cleanup: Function }} 화면 요소와 정리 함수
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
              class="btn w-100 py-2 mb-3"
              style="background-color: #FFFFFF; color: #1F1F1F; border-color: #747775"
              type="button">
            <img src="/assets/ui/google-g.png" alt="구글 아이콘" class="me-2" width="21" height="21" />
            Google로 계속하기
          </button>

          <button
              id="naver-login-button"
              class="btn w-100 py-2 mb-3 text-white"
              style="background-color: #03C75A;"
              type="button">
            <strong class="me-2">N</strong>
            네이버로 계속하기
          </button>

          <!-- OAuth 인증 및 연결 실패 안내 -->
          <div
              class="alert alert-danger mb-3 d-none login-error"
              role="alert"
              aria-live="polite">
            <p class="feedback small mb-0"></p>
          </div>
        </div>
      </section>
    </div>
  `;

  // main 브랜치의 공통 Header를 사용합니다.
  // 테마 전환은 공통 Header 내부에서 처리합니다.
  const element = createHeaderLayout({
    content,
    currentUser,
    navigate,
    showGroupBadge: false,
    showSeasonInfo: false,
    showConnectionStatus: false,
  });

  // 공급자별 버튼과 공통 오류 표시 요소를 찾습니다.
  const googleButton = /** @type {HTMLButtonElement | null} */ (
      element.querySelector('#google-login-button')
  );
  const naverButton = /** @type {HTMLButtonElement | null} */ (
      element.querySelector('#naver-login-button')
  );
  const feedback = element.querySelector('.feedback');
  const loginError = element.querySelector('.login-error');

  if (!googleButton || !naverButton || !feedback || !loginError) {
    throw new Error('로그인 화면 요소를 찾을 수 없습니다.');
  }

  // OAuth Callback 실패 시 전달된 오류를 표시합니다.
  const params = new URLSearchParams(location.search);
  const oauthError = params.get('oauthError');

  const oauthErrorMessages = {
    OAUTH_ACCESS_DENIED:
        '소셜 로그인이 취소되었습니다. 다시 로그인해 주세요.',
    OAUTH_INVALID_STATE:
        '로그인 요청을 확인할 수 없습니다. 다시 로그인해 주세요.',
    OAUTH_SESSION_EXPIRED:
        '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.',
    OAUTH_EMAIL_CONFLICT:
        '이미 가입된 이메일입니다. 기존에 가입한 소셜 계정으로 로그인해 주세요.',
  };

  if (oauthError) {
    feedback.textContent =
        oauthErrorMessages[oauthError] ||
        '소셜 인증에 실패했습니다. 다시 로그인해 주세요.';

    loginError.classList.remove('d-none');
  }

  /**
   * 선택한 소셜 공급자의 OAuth 인증을 시작합니다.
   * 인증 중 버튼 중복 클릭을 방지하고, 실패하면 다시 활성화합니다.
   * @param {'GOOGLE' | 'NAVER'} provider 로그인 공급자
   * @returns {Promise<void>} OAuth 이동 또는 오류 처리 완료
   */
  const onLoginClick = async (provider) => {
    const targetButton =
        provider === 'NAVER' ? naverButton : googleButton;

    // 로그인 연결 중 중복 요청을 방지합니다.
    googleButton.disabled = true;
    naverButton.disabled = true;
    targetButton.textContent = '로그인 연결 중...';

    feedback.textContent = '';
    loginError.classList.add('d-none');

    try {
      // 기존 초대 가입 문맥을 유지합니다.
      const loginParams = new URLSearchParams(location.search);
      const returnRoute =
          loginParams.get('returnRoute') === 'INVITE'
              ? 'INVITE'
              : 'DEFAULT';

      // 선택한 공급자의 인가 URL을 서버에서 발급받습니다.
      const result = await createOAuthAttempt(returnRoute, provider);
      location.assign(result.authorizationUrl);
    } catch (error) {
      feedback.textContent =
          error instanceof Error
              ? error.message
              : '로그인 중 오류가 발생했습니다.';

      loginError.classList.remove('d-none');

      // 실패 시 두 버튼을 다시 사용할 수 있게 복원합니다.
      googleButton.disabled = false;
      naverButton.disabled = false;
      googleButton.textContent = 'Google로 계속하기';
      naverButton.textContent = 'Naver로 계속하기';
    }
  };

  // 각 버튼에 공급자별 이벤트를 연결합니다.
  const onGoogleClick = () => onLoginClick('GOOGLE');
  const onNaverClick = () => onLoginClick('NAVER');

  googleButton.addEventListener('click', onGoogleClick);
  naverButton.addEventListener('click', onNaverClick);

  return {
    element,
    cleanup: () => {
      googleButton.removeEventListener('click', onGoogleClick);
      naverButton.removeEventListener('click', onNaverClick);
    },
  };
}
