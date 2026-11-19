// @ts-check

/**
 * 내부 링크의 Path와 Query, Fragment를 보존한 CSR 이동 경로를 반환합니다.
 *
 * @param {{pathname: string, search?: string, hash?: string}} link
 * @returns {string}
 */
export function linkNavigationTarget(link) {
    return `${link.pathname}${link.search ?? ''}${link.hash ?? ''}`;
}
