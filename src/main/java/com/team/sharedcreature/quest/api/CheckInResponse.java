package com.team.sharedcreature.quest.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * checkInId : 저장된 체크인 ID
 * checkInDate : 체크인이 기록된 KST 날짜
 * checkedInAt : 체크인이 처리된 UTC 시각
 * isMorning : 기상 체크인 시간대에 해당하는지 여부
 * completedQuestIds : 체크인으로 자동 완료된 퀘스트 완료 기록 ID 목록
 */
@Getter
@RequiredArgsConstructor
public class CheckInResponse {

    /** 저장된 체크인 기록의 ID입니다. */
    private final Long checkInId;

    /** 체크인한 날짜입니다. KST 기준 YYYY-MM-DD로됩니다. */
    private final LocalDate checkInDate;

    /** 체크인이 처리된 시각입니다. UTC ISO 8601 형식으로됩니다. */
    private final Instant checkedInAt;

    /** KST 기준 05:00 이상 09:00 미만에 체크인했으면 true입니다. */
    @JsonProperty("isMorning")
    private final boolean isMorning;

    /** 체크인으로 자동 완료된 퀘스트 완료 기록 ID 목록입니다. 없으면 빈 목록입니다. */
    private final List<Long> completedQuestIds;
}
