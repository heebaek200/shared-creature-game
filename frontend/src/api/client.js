// @ts-check

let csrfToken = '';

/** 서버 오류 code와 필드 오류를 보존합니다. */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? '요청을 처리하지 못했습니다.');
    this.status = status;
    this.code = body?.code ?? 'UNKNOWN_ERROR';
    this.fieldErrors = body?.fieldErrors ?? [];
    this.traceId = body?.traceId ?? null;
  }
}

/**
 * Cookie, CSRF, JSON·Multipart와 공통 Envelope를 한 경계에서 처리합니다.
 * @param {string} path API 경로
 * @param {{method?: string, body?: unknown, multipart?: FormData}} options 요청 옵션
 */
export async function apiRequest(path, options = {}) {
  return executeRequest(path, options, true);
}

/** CSRF 실패 시 새 Token으로 한 번만 안전하게 재시도합니다. */
async function executeRequest(path, options, allowCsrfRetry) {
  const method = options.method ?? 'GET';
  const headers = new Headers({ Accept: 'application/json' });
  let body;
  if (options.multipart) body = options.multipart;
  else if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.body);
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers.set('X-CSRF-TOKEN', await getCsrfToken());
  }
  const response = await fetch(path, { method, headers, body, credentials: 'include' });
  const payload = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok && allowCsrfRetry && payload?.code === 'CSRF_INVALID') {
    clearCsrfToken();
    return executeRequest(path, options, false);
  }
  if (!response.ok) throw new ApiError(response.status, payload);
  return payload?.data;
}

/** 상태 변경 요청에 사용할 현재 세션 CSRF Token을 메모리에만 보관합니다. */
export async function getCsrfToken() {
  if (csrfToken) return csrfToken;
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'include' });
  if (!response.ok) throw new ApiError(response.status, await response.json().catch(() => null));
  const envelope = await response.json();
  csrfToken = envelope.data.token;
  return csrfToken;
}

/** 로그아웃·세션 교체 시 메모리에 보관한 CSRF Token을 제거합니다. */
export function clearCsrfToken() {
  csrfToken = '';
}
