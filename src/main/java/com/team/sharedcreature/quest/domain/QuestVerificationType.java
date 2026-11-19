package com.team.sharedcreature.quest.domain;

/** 퀘스트 판정 방식입니다. DB·API의 기존 유형 코드를 유지합니다. */
public enum QuestVerificationType {
    SELF_CHECK,
    SERVER_EVENT,
    AGGREGATE,
    PHOTO_EXIF
}
