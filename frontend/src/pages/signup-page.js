
// @ts-check
import {
    getSignupContext,
    completeSignup
} from '../features/auth/api.js';

/** 가입 대기 사용자의 닉네임 확정 Page를 생성합니다. */
export function signupPage({ navigate }) {
    const element = document.createElement('main');
    element.className = 'min-vh-100 bg-body-tertiary';

    element.innerHTML = `
        <header class="border-bottom bg-body">
            <div class="container-fluid px-4 py-3 d-flex justify-content-between align-items-center">
                <a href="/" class="text-decoration-none fw-semibold text-success">
                    다 같이 키우는 생명체
                </a>

                <button class="btn btn-sm btn-outline-secondary" id="theme-toggle" type="button">
                    테마 전환 ◐
                </button>
            </div>
        </header>

        <div class="container-fluid px-3 px-md-4 py-4">
            <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-3">
                <div>
                    <h2 class="fw-bold mb-2">회원가입</h2>
                   
                </div>
            </div>

            <div class="mx-auto" style="max-width: 620px;">
                <section class="card shadow-none border rounded-4 mb-4">
                    <div class="card-body p-4">
                        <h5 class="fw-semibold mb-4">회원가입</h5>

                        <div class="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
                            <p class="mb-0">
                                소셜 인증이 완료되었습니다. 서비스 닉네임을 확정해 주세요.
                            </p>
                            <span class="badge rounded-pill text-bg-success bg-opacity-10 text-success border px-3 py-2">
                                가입 대기 30분
                            </span>
                        </div>

                        <form id="signup-form" novalidate>
                            <div class="mb-3">
                                <label for="provider" class="form-label fw-semibold">
                                    가입 방식
                                </label>
                                <input
                                    class="form-control bg-body-secondary"
                                    id="provider"
                                    readonly
                                >
                            </div>

                            <div class="mb-3">
                                <label for="email" class="form-label fw-semibold">
                                    이메일
                                </label>
                                <input
                                    class="form-control bg-body-secondary"
                                    id="email"
                                    readonly
                                >
                            </div>

                            <div class="mb-3">
                                <label for="nickname" class="form-label fw-semibold">
                                    닉네임
                                </label>

                                <div class="d-flex gap-2 align-items-start">
                                    <input
                                        class="form-control"
                                        id="nickname"
                                        type="text"
                                        minlength="2"
                                        maxlength="20"
                                        placeholder="닉네임을 입력하세요"
                                        autocomplete="nickname"
                                        required
                                    >

                                    <button
                                        class="btn btn-outline-secondary text-nowrap"
                                        id="check-nickname"
                                        type="button"
                                    >
                                        중복 확인
                                    </button>
                                </div>

                                <div class="form-text">
                                    2~20자 · 중복 확인 후 가입
                                </div>
                            </div>

                            <div
                                class="alert alert-secondary border-0 border-start border-4 mb-3"
                                role="status"
                                id="feedback"
                            >
                                가입 정보를 조회하고 있습니다.
                            </div>

                            <div class="d-flex gap-2 flex-wrap">
                                <button
                                    class="btn btn-success"
                                    type="submit"
                                    disabled
                                >
                                    가입 완료
                                </button>

                                <button
                                    class="btn btn-outline-secondary"
                                    id="back-login"
                                    type="button"
                                >
                                    로그인으로 돌아가기
                                </button>
                            </div>
                        </form>
                    </div>
                </section>
            </div>
        </div>
    `;

    const form = element.querySelector('#signup-form');
    const provider = element.querySelector('#provider');
    const email = element.querySelector('#email');
    const nickname = element.querySelector('#nickname');
    const submit = element.querySelector('button[type="submit"]');
    const checkButton = element.querySelector('#check-nickname');
    const feedback = element.querySelector('#feedback');
    const themeToggle = element.querySelector('#theme-toggle');
    const backLogin = element.querySelector('#back-login');

    let disposed = false;
    let contextLoaded = false;

    /** 닉네임 정규화 및 길이 검사 */
    function normalizeNickname(value) {
        return value.normalize('NFKC').trim();
    }

    function validateNickname() {
        const normalized = normalizeNickname(nickname.value);
        nickname.value = normalized;

        const length = Array.from(normalized).length;

        if (length < 2 || length > 20) {
            feedback.textContent = '닉네임은 2~20자로 입력해 주세요.';
            feedback.className = 'alert alert-warning border-0 mb-3';
            return false;
        }

        return true;
    }

    /** 가입 대기 정보를 조회합니다. */
    async function loadSignupContext() {
        try {
            const data = await getSignupContext();

            if (disposed) return;

            provider.value = data.provider;
            email.value = data.email;
            nickname.value = data.suggestedNickname ?? '';

            contextLoaded = true;
            submit.disabled = false;

            feedback.textContent = '닉네임을 입력하고 가입을 진행해 주세요.';
            feedback.className = 'alert alert-secondary border-0 border-start border-4 mb-3';
        } catch (error) {
            if (disposed) return;

            feedback.textContent =
                error?.code === 'SIGNUP_CONTEXT_EXPIRED'
                    ? '가입 대기 시간이 만료되었습니다. 다시 로그인해 주세요.'
                    : '가입 정보를 불러오지 못했습니다.';

            feedback.className = 'alert alert-danger mb-3';
        }
    }

    /** 닉네임 형식을 확인합니다. */
    function onCheckNickname() {
        if (!validateNickname()) return;

        feedback.textContent =
            '사용 가능한 닉네임입니다';
        feedback.className = 'alert alert-info mb-3';
    }

    /** 가입 완료 */
    async function onSubmit(event) {
        event.preventDefault();

        if (!contextLoaded || !validateNickname()) return;

        submit.disabled = true;
        checkButton.disabled = true;

        feedback.textContent = '회원가입을 진행하고 있습니다.';
        feedback.className = 'alert alert-info mb-3';

        try {
            await completeSignup(nickname.value);

            if (!disposed) {
                navigate('/');
            }
        } catch (error) {
            if (disposed) return;

            feedback.textContent =
                error?.message || '회원가입에 실패했습니다. 다시 시도해 주세요.';

            feedback.className = 'alert alert-danger mb-3';

            submit.disabled = false;
            checkButton.disabled = false;
        }
    }

    /** 테마 전환 */
    function onThemeToggle() {
        const currentTheme =
            document.documentElement.dataset.bsTheme || 'light';

        const nextTheme = currentTheme === 'dark' ? 'light' : 'dark';

        document.documentElement.dataset.bsTheme = nextTheme;
        localStorage.setItem('theme', nextTheme);
    }

    function onBackLogin() {
        navigate('/login');
    }

    form.addEventListener('submit', onSubmit);
    checkButton.addEventListener('click', onCheckNickname);
    themeToggle.addEventListener('click', onThemeToggle);
    backLogin.addEventListener('click', onBackLogin);

    nickname.addEventListener('input', () => {
        checkButton.disabled = false;
    });

    void loadSignupContext();

    return {
        element,

        cleanup: () => {
            disposed = true;

            form.removeEventListener('submit', onSubmit);
            checkButton.removeEventListener('click', onCheckNickname);
            themeToggle.removeEventListener('click', onThemeToggle);
            backLogin.removeEventListener('click', onBackLogin);
        }
    };
}
