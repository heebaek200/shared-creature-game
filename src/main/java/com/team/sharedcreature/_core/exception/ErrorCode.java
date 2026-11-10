package com.team.sharedcreature._core.exception;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드와 HTTP 상태 및 기본 사용자 메시지를 함께 관리합니다.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식을 확인해 주세요."),
    INVALID_FIELD_VALUE(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    SLOT_MISMATCH(HttpStatus.BAD_REQUEST, "아이템과 장착 슬롯이 일치하지 않습니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료됐습니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 만료됐습니다."),
    ROLE_FORBIDDEN(HttpStatus.FORBIDDEN, "요청 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자원을 찾을 수 없습니다."),
    SIGNUP_CONTEXT_EXPIRED(HttpStatus.CONFLICT, "가입 대기 시간이 만료됐습니다."),
    NICKNAME_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    GROUP_NAME_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 그룹 이름입니다."),
    GROUP_FULL(HttpStatus.CONFLICT, "그룹 정원이 가득 찼습니다."),
    ALREADY_IN_GROUP(HttpStatus.CONFLICT, "이미 활성 그룹에 소속되어 있습니다."),
    JOIN_COOLDOWN_ACTIVE(HttpStatus.CONFLICT, "그룹 가입 제한 시간이 끝난 뒤 다시 시도해 주세요."),
    INVITE_INVALID(HttpStatus.CONFLICT, "현재 사용할 수 없는 초대입니다."),
    INACTIVITY_REQUIREMENT_NOT_MET(HttpStatus.CONFLICT, "비활동 강퇴 조건을 충족하지 않습니다."),
    PRIMARY_TRANSFER_REQUIRED(HttpStatus.CONFLICT, "주 관리자 이전이 필요합니다."),
    CREATURE_SELECTION_CLOSED(HttpStatus.CONFLICT, "크리처 선택 시간이 종료됐습니다."),
    QUEST_PERIOD_ENDED(HttpStatus.CONFLICT, "퀘스트 유효 기간이 종료됐습니다."),
    QUEST_COMPLETION_NOT_ALLOWED(HttpStatus.CONFLICT, "직접 완료할 수 없는 퀘스트입니다."),
    PHOTO_ATTEMPT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "사진 제출 가능 횟수를 초과했습니다."),
    FIRST_USE_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT, "최초 장착 동의가 필요합니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "결제 금액이 일치하지 않습니다."),
    PAYMENT_PENDING(HttpStatus.CONFLICT, "결제 결과를 확인하고 있습니다."),
    PAYMENT_RESULT_UNKNOWN(HttpStatus.CONFLICT, "결제 결과를 확인할 수 없습니다."),
    REFUND_NOT_ALLOWED_USED_ITEM(HttpStatus.CONFLICT, "사용한 아이템은 환불할 수 없습니다."),
    REFUND_ALREADY_ACTIVE(HttpStatus.CONFLICT, "진행 중인 환불 요청이 있습니다."),
    PG_CANCEL_RESULT_UNKNOWN(HttpStatus.CONFLICT, "결제 취소 결과를 확인할 수 없습니다."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청에서 상태가 변경됐습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "멱등 키가 다른 요청에 사용됐습니다."),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일은 10 MiB를 초과할 수 없습니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다."),

    // OAuth Callback은 JSON 오류가 아닌 내부 CSR 경로로 이동하지만 같은 예외 기반을 사용합니다.
    OAUTH_ID_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "OAuth 인증 정보를 확인할 수 없습니다."),
    OAUTH_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "OAuth 공급자에 연결할 수 없습니다."),
    OAUTH_STATE_INVALID(HttpStatus.CONFLICT, "유효하지 않은 로그인 요청입니다."),
    OAUTH_SESSION_EXPIRED(HttpStatus.CONFLICT, "OAuth 인증 세션이 만료됐습니다."),
    OAUTH_SESSION_MISMATCH(HttpStatus.CONFLICT, "로그인을 시작한 세션과 일치하지 않습니다."),
    SOCIAL_EMAIL_PROVIDER_CONFLICT(HttpStatus.CONFLICT, "이미 다른 소셜 공급자로 가입한 이메일입니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
