package com.team.sharedcreature.quest.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 사용자 활동을 기록할 때 사용하는 이벤트 유형입니다. */
public enum UserActivityEventType {
    CHECK_IN("check_in"),
    SERVICE_CHECK_IN("SERVICE_CHECK_IN"),
    QUEST_COMPLETED("QUEST_COMPLETED");

    private final String code;

    UserActivityEventType(String code) {
        this.code = code;
    }

    /** 기존 VARCHAR에 기록된 코드를 대소문자까지 그대로 유지합니다. */
    @Converter
    public static class JpaConverter implements AttributeConverter<UserActivityEventType, String> {
        @Override
        public String convertToDatabaseColumn(UserActivityEventType value) {
            return value == null ? null : value.code;
        }

        @Override
        public UserActivityEventType convertToEntityAttribute(String code) {
            if (code == null) {
                return null;
            }
            for (UserActivityEventType value : UserActivityEventType.values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown UserActivityEventType code: " + code);
        }
    }
}
