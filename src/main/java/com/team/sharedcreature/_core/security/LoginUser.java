package com.team.sharedcreature._core.security;

import java.io.Serializable;
import java.util.Set;

/**
 * 인증 세션과 Controller 사이에서 사용하는 최소 로그인 사용자 정보입니다.
 *
 * @param id 내부 사용자 ID
 * @param nickname 표시 닉네임
 * @param roles 현재 전역·그룹 역할
 */
public record LoginUser(Long id, String nickname, Set<String> roles) implements Serializable {
}
