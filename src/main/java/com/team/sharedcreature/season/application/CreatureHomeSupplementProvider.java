package com.team.sharedcreature.season.application;

import com.team.sharedcreature.season.api.CreatureHomeResponse.ContributionActivityResponse;
import com.team.sharedcreature.season.api.CreatureHomeResponse.OnlineMemberResponse;
import com.team.sharedcreature.season.domain.GroupCreature;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 퀘스트·랭킹·장착·Presence 이슈가 제공할 크리처 홈 부가 Snapshot 경계입니다.
 */
public interface CreatureHomeSupplementProvider {

    /** 지정 크리처와 요청자를 기준으로 현재 부가 Snapshot을 반환합니다. */
    Supplement getSupplement(GroupCreature creature, Long requesterId, Instant now);

    /** 연계 기능의 확정 데이터와 그룹 Stream 커서를 묶습니다. */
    record Supplement(
            long todayExperience,
            List<ContributionActivityResponse> recentContributions,
            List<OnlineMemberResponse> onlineMembers,
            UUID streamInstanceId,
            long sequence
    ) {
    }
}
