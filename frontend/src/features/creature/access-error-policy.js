// @ts-check

/**
 * 크리처 화면의 인증·그룹 권한 오류가 발생했을 때 접근을 종료할 경로를 반환합니다.
 * 일시적인 통신 오류와 서버 오류는 기존 Snapshot 복구 흐름에 맡깁니다.
 *
 * @param {{code?: string}|null|undefined} error
 * @returns {'/login'|'/403'|null}
 */
export function creatureAccessErrorRoute(error) {
    if (['AUTH_REQUIRED', 'SESSION_EXPIRED'].includes(error?.code ?? '')) {
        return '/login';
    }
    if (error?.code === 'ROLE_FORBIDDEN') return '/403';
    return null;
}
