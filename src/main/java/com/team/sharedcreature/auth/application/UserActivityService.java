package com.team.sharedcreature.auth.application;

import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.auth.infrastructure.UserDailyActivityRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 인증된 API 요청으로 발생한 사용자 활동을 기록합니다.
 *
 * 모든 요청마다 DB를 갱신하지 않고 마지막 기록 후
 * 10분 이상 경과한 경우에만 활동 시각을 영속화합니다.
 */
@Service
public class UserActivityService {

    private static final Duration ACTIVITY_WRITE_INTERVAL =
            Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final UserDailyActivityRepository dailyActivityRepository;
    private final GroupMembershipRepository membershipRepository;
    private final Clock clock;

    /**
     * 활동 기록에 필요한 Repository와 서버 시계를 주입받습니다.
     *
     * Clock을 외부에서 주입받아 테스트에서 시간을 고정할 수 있도록 합니다.
     * 갱신 간격은 모든 인증 요청에 동일하게 적용합니다.
     */
    public UserActivityService(
            UserRepository userRepository,
            UserDailyActivityRepository dailyActivityRepository,
            GroupMembershipRepository membershipRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.dailyActivityRepository = dailyActivityRepository;
        this.membershipRepository = membershipRepository;
        this.clock = clock;
    }

    /**
     * 정상 처리된 인증 API 요청의 마지막 활동 시각을 기록합니다.
     *
     * 현재 시각으로부터 10분 전을 기준으로 조건부 UPDATE를 수행합니다.
     * 아직 10분이 지나지 않았다면 DB의 데이터는 변경하지 않습니다.
     *
     * @param userId 인증된 사용자 ID
     * @return 실제 갱신한 행의 수
     */
    @Transactional
    public int recordSuccessfulRequest(Long userId) {
        // 주입된 Clock으로 현재 시각과 갱신 기준을 계산합니다.
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(ACTIVITY_WRITE_INTERVAL);

        var membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .filter(activeMembership -> "ACTIVE".equals(activeMembership.getGroup().getStatus()))
                .orElse(null);
        dailyActivityRepository.upsert(
                userId,
                membership == null ? null : membership.getId(),
                membership == null ? null : membership.getGroup().getId(),
                now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                now
        );

        // 단일 조건부 UPDATE로 동시 요청의 중복 저장을 방지합니다.
        return userRepository.updateLastActiveAtIfDue(
                userId,
                now,
                threshold
        );
    }
}
