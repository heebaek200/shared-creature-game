package com.team.sharedcreature.quest.api;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 현재 퀘스트 조회 요청의 Query Parameter를 담습니다.
 */
@Getter
@Setter
@NoArgsConstructor
public class CurrentQuestsRequest {

    /**
     * 조회할 퀘스트 기간 유형입니다.
     *
     * DAILY는 일일 퀘스트, WEEKLY는 주간 퀘스트입니다.
     * 값이 없으면 두 기간 유형을 모두 조회합니다.
     */
    private String periodType;
}
