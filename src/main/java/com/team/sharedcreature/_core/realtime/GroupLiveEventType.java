package com.team.sharedcreature._core.realtime;

/** 그룹 실시간 이벤트의 종류입니다. Enum 이름을 STOMP 메시지에 사용합니다. */
public enum GroupLiveEventType {
    PRESENCE_JOINED,
    PRESENCE_LEFT,
    AVATAR_EQUIPMENT_CHANGED,
    CREATURE_UPDATED,
    CONTRIBUTION_UPDATED
}
