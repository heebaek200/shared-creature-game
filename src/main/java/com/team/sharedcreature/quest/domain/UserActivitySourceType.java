package com.team.sharedcreature.quest.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 사용자 활동을 생성한 출처입니다. */
public enum UserActivitySourceType {
    SERVICE_CHECK_IN("service_check_in"),
    MANUAL_TEST_SEED("MANUAL_TEST_SEED"),
    QUEST_COMPLETION("QUEST_COMPLETION");

    private final String code;

    UserActivitySourceType(String code) {
        this.code = code;
    }

    /** 기존 VARCHAR에 기록된 코드를 대소문자까지 그대로 유지합니다. */
    @Converter
    public static class JpaConverter implements AttributeConverter<UserActivitySourceType, String> {
        @Override
        public String convertToDatabaseColumn(UserActivitySourceType value) {
            return value == null ? null : value.code;
        }

        @Override
        public UserActivitySourceType convertToEntityAttribute(String code) {
            if (code == null) {
                return null;
            }
            for (UserActivitySourceType value : UserActivitySourceType.values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown UserActivitySourceType code: " + code);
        }
    }
}
