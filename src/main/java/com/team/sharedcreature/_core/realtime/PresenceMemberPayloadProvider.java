package com.team.sharedcreature._core.realtime;

/** Presence 입장 이벤트에 필요한 구성원 화면 정보를 조회하는 경계입니다. */
public interface PresenceMemberPayloadProvider {

    /** 현재 그룹의 지정 사용자를 입장 이벤트 Payload로 구성합니다. */
    GroupLivePayloads.PresenceJoined getPresenceJoined(Long groupId, Long userId);
}
