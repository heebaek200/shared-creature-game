const THEME_KEY = 'theme';
const THEME_OPTIONS = ['system', 'light', 'dark'];
const colorScheme = window.matchMedia('(prefers-color-scheme: dark)');

function getPreference() {
    const saved = localStorage.getItem(THEME_KEY);
    return THEME_OPTIONS.includes(saved) ? saved : 'system';
}

function applyTheme(preference) {
    const resolvedTheme =
        preference === 'system'
            ? (colorScheme.matches ? 'dark' : 'light')
            : preference;

    document.documentElement.dataset.bsTheme = resolvedTheme;
}

function savePreference(preference) {
    if (!THEME_OPTIONS.includes(preference)) return;

    localStorage.setItem(THEME_KEY, preference);
    applyTheme(preference);
}

/* 첫 화면을 그리기 전에 저장된 테마를 적용 */
export function initializeTheme() {
    applyTheme(getPreference());

    colorScheme.addEventListener('change', () => {
        if (getPreference() === 'system') {
            applyTheme('system');
        }
    });
}

/* Header에 넣을 테마 선택 요소 */
export function createThemeToggle() {
    const dropdown = document.createElement('details');
    dropdown.className = 'theme-dropdown';

    dropdown.innerHTML = `
    <summary class="btn btn-sm btn-outline-secondary text-nowrap" aria-label="테마 설정">
      <span class="theme-toggle-label">테마 설정</span>
      <span class="theme-toggle-icon" aria-hidden="true">◐</span>
    </summary>

    <div class="theme-menu" role="group" aria-label="테마 선택">
      <button type="button" class="theme-option" data-theme="system">
        시스템 설정
      </button>
      <button type="button" class="theme-option" data-theme="light">
        라이트
      </button>
      <button type="button" class="theme-option" data-theme="dark">
        다크
      </button>
    </div>
  `;
    const summary = dropdown.querySelector('summary');
    const options = dropdown.querySelectorAll('[data-theme]');

    function updateSelectedOption(preference) {
        options.forEach((option) => {
            const isSelected = option.dataset.theme === preference;
            option.classList.toggle('is-selected', isSelected);
            option.setAttribute('aria-pressed', String(isSelected));
        });
    }

    updateSelectedOption(getPreference());

    dropdown.addEventListener('click', (event) => {
        if (!(event.target instanceof Element)) return;

        const option = event.target.closest('[data-theme]');
        if (!option) return;

        const preference = option.dataset.theme;
        savePreference(preference);
        updateSelectedOption(preference);

        dropdown.open = false;
        summary.focus();
    });

    dropdown.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && dropdown.open) {
            dropdown.open = false;
            summary.focus();
        }
    });

    return dropdown;
}