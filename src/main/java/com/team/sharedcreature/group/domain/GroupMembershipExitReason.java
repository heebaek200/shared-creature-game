package com.team.sharedcreature.group.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 그룹의 탈퇴·강퇴 사유입니다. */
public enum GroupMembershipExitReason {
    SELF("SELF"),
    INACTIVE("INACTIVE"),
    REPORT("REPORTED");

    private final String databaseCode;

    GroupMembershipExitReason(String databaseCode) {
        this.databaseCode = databaseCode;
    }

    /** 신고 강퇴의 기존 DB 코드 REPORTED와 활성 멤버십의 null을 유지합니다. */
    @Converter
    public static class JpaConverter implements AttributeConverter<GroupMembershipExitReason, String> {
        @Override
        public String convertToDatabaseColumn(GroupMembershipExitReason value) {
            return value == null ? null : value.databaseCode;
        }

        @Override
        public GroupMembershipExitReason convertToEntityAttribute(String code) {
            if (code == null) {
                return null;
            }
            return switch (code) {
                case "SELF" -> SELF;
                case "INACTIVE" -> INACTIVE;
                case "REPORTED" -> REPORT;
                default -> throw new IllegalArgumentException("Unknown group membership exit reason: " + code);
            };
        }
    }
}
