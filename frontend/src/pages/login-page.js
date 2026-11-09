
// @ts-check
import { createOAuthAttempt } from '../features/auth/api.js';

/**
 * Google 로그인 진입 Page를 생성합니다.
 */
export function loginPage() {
  const element = document.createElement('main');
  element.className = 'app-shell min-vh-100 bg-body-tertiary';

  element.innerHTML = `
        <div class="container py-4 py-md-5">

            <!-- 상단 헤더 -->
            <header class="d-flex align-items-center justify-content-between mb-4 mb-md-5">
                <a href="/" class="text-decoration-none text-success fw-bold fs-5">
                    <span class="me-2">●</span>
                    다 같이 키우는 생명체
                </a>

                <button
                    type="button"
                    class="btn btn-outline-secondary btn-sm theme-toggle"
                    aria-label="테마 전환">
                    <span class="theme-icon"></span>
                    <span class="theme-label">다크 모드</span>
                </button>
            </header>

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

  const button = element.querySelector('#google-login-button');
  const feedback = element.querySelector('.feedback');
  const loginError = element.querySelector('.login-error');
  const themeButton = element.querySelector('.theme-toggle');
  const themeIcon = element.querySelector('.theme-icon');
  const themeLabel = element.querySelector('.theme-label');

  if (!button || !feedback || !loginError || !themeButton || !themeIcon || !themeLabel) {
    throw new Error('로그인 화면 요소를 찾을 수 없습니다.');
  }

  // 현재 테마에 맞춰 버튼 표시
  const updateThemeButton = () => {
    const isDark = document.documentElement.dataset.bsTheme === 'dark';

    themeLabel.textContent = isDark ? '테마 변환' : '테마 변환';
  };

  updateThemeButton();

  // 테마 전환
  const onTheme = () => {
    const currentTheme = document.documentElement.dataset.bsTheme || 'light';
    const nextTheme = currentTheme === 'dark' ? 'light' : 'dark';

    document.documentElement.dataset.bsTheme = nextTheme;
    localStorage.setItem('theme', nextTheme);

    updateThemeButton();
  };

  // Google 로그인
  const onClick = async () => {
    button.disabled = true;
    button.textContent = '로그인 연결 중...';
    feedback.textContent = '';
    loginError.classList.add('d-none');


    try {
      const result = await createOAuthAttempt();
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

  themeButton.addEventListener('click', onTheme);
  button.addEventListener('click', onClick);

  return {
    element,
    cleanup: () => {
      themeButton.removeEventListener('click', onTheme);
      button.removeEventListener('click', onClick);
    },
  };
}
