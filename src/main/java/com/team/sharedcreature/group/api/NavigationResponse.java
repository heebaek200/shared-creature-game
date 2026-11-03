package com.team.sharedcreature.group.api;

/** 초대 수락 또는 거절 뒤 CSR이 이동할 허용 경로를 제공합니다. */
public record NavigationResponse(String nextRoute, String reasonCode) {
}
