
package com.team.sharedcreature.admin.api;

/**
 * 전체 관리자 그룹 검색 결과의 한 항목입니다.
 *
 * 관리자가 그룹을 식별하고 선택하는 데 필요한 정보만 제공합니다.
 * 가입 가능 여부와 같은 일반 회원용 필드는 포함하지 않습니다.
 *
 * @param groupId 그룹 PK
 * @param name 그룹 표시 이름
 * @param visibility 공개 여부
 * @param currentMemberCount 현재 그룹 회원 수
 * @param capacity 그룹 최대 정원
 */
public record AdminGroupSearchResponse(
        Long groupId,
        String name,
        String visibility,
        int currentMemberCount,
        int capacity
) {
}
