package com.team.sharedcreature._core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * OAuth state와 초대 Token 원문을 저장하지 않도록 SHA-256 Hash를 생성합니다.
 */
public final class Hashing {

    private Hashing() {
    }

    /**
     * 문자열의 SHA-256 Hash를 소문자 16진수로 반환합니다.
     *
     * @param value Hash할 원문
     * @return 64자 SHA-256 Hash
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
