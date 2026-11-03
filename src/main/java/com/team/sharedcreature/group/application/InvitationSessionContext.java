package com.team.sharedcreature.group.application;

import java.io.Serializable;

/** 원문 초대 Token을 보관하지 않는 서버 세션 초대 문맥입니다. */
public record InvitationSessionContext(Long inviteLinkId) implements Serializable {
    public static final String SESSION_KEY = "INVITATION_CONTEXT";
}
