package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.ServiceCheckIn;
import com.team.sharedcreature.quest.domain.UserActivityEvent;
import com.team.sharedcreature.quest.infrastructure.QuestAssignmentRepository;
import com.team.sharedcreature.quest.infrastructure.QuestPeriodRepository;
import com.team.sharedcreature.quest.infrastructure.ServiceCheckInRepository;
import com.team.sharedcreature.quest.infrastructure.UserActivityEventRepository;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.Optional;

/**
 * 서비스 체크인 처리와 관련 활동 기록 생성을 담당합니다.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    /** 체크인으로 조건을 충족한 퀘스트의 완료와 보상을 처리합니다. */
    private final QuestCompletionService questCompletionService;

    /** 체크인을 요청한 사용자를 조회하고 동시 요청을 직렬화합니다. */
    private final UserRepository userRepository;

    /** 사용자의 활성 그룹 멤버십과 그룹 정보를 조회합니다. */
    private final GroupMembershipRepository membershipRepository;

    /** 사용자별 체크인 기록을 조회하고 저장합니다. */
    private final ServiceCheckInRepository serviceCheckInRepository;

    /** 체크인에 연결할 서버 활동 기록을 저장합니다. */
    private final UserActivityEventRepository activityEventRepository;

    /** 현재 활성 시즌을 조회합니다. */
    private final SeasonQueryService seasonQueryService;

    /** 사용자 그룹의 현재 시즌 참여 정보를 조회하고 잠급니다. */
    private final GroupSeasonRepository groupSeasonRepository;

    /** 현재 시각을 가져오는 공통 UTC 시계입니다. */
    private final Clock clock;

    /** 체크인 날짜와 오전 시간대를 계산하는 기준 시간대입니다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 현재 활성 시즌의 일일 퀘스트 기간을 조회합니다. */
    private final QuestPeriodRepository periodRepository;

    /** 해당 기간에 배정된 퀘스트와 스냅샷 조회합니다. */
    private final QuestAssignmentRepository assignmentRepository;

    /**
     * 체크인 시각이 KST 기상 인정 시간대에 포함되는지 판정합니다.
     * ZoneId에는 KST 기준이 없어서 기준 설정
     * @param checkedInAt 판정할 체크인 시각
     * @return KST 기준 05:00 이상 09:00 미만이면 true
     */
    private boolean isMorningCheckIn(Instant checkedInAt) {
        LocalTime checkInTime = checkedInAt.atZone(KST).toLocalTime();

        return !checkInTime.isBefore(LocalTime.of(5, 0))
                && checkInTime.isBefore(LocalTime.of(9, 0));
    }

    /**
     * 현재 일일 퀘스트 배정 중 기상 체크인 퀘스트를 찾습니다.
     *
     * @return D-M-01 배정이 있으면 해당 배정, 없으면 빈 Optional
     */
    private Optional<QuestAssignment> findCurrentMorningQuestAssignment(Instant now) {

        // 일일퀘스트 기간 조회
        Long seasonId = seasonQueryService.findCurrentSeason().getId();
        var currentPeriod = periodRepository.findCurrentBySeasonAndType(
                seasonId,
                "DAILY",
                now
        );

        if (currentPeriod.isEmpty()) {
            return Optional.empty();
        }

        // 기간내 할당 퀘스트 조회
        var assignments = assignmentRepository.findAllWithSnapshotByPeriodId(
                currentPeriod.get().getId()
        );

        // D-M-01 찾기
        for (QuestAssignment assignment : assignments) {
            if ("D-M-01".equals(assignment.getQuestTemplate().getCode())) {
                return Optional.of(assignment);
            }
        }
        // 없을 시 빈 Optional 리턴
        return Optional.empty();
    }

    /**
     * 사용자의 활성 그룹 멤버십을 조회합니다.
     *
     * @param userId 멤버십을 확인할 사용자 ID
     * @return 그룹 정보가 함께 조회된 활성 멤버십
     * @throws BusinessException 활성 그룹 멤버십이 없으면 403 ROLE_FORBIDDEN
     */
    private GroupMembership findActiveMembership(Long userId) {

        // 403 ROLE_FORBIDDEN 요청자 권한 부족
        return membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));
    }

    /**
     * 체크인 중복 요청이 동시에 진행되지 않도록 사용자를 쓰기 잠금으로 조회합니다.
     *
     * @param userId 잠글 사용자 ID
     * @throws BusinessException 사용자를 찾지 못하면 401 AUTH_REQUIRED
     */
    private User findUserForUpdate(Long userId) {

        // 401 AUTH_REQUIRED 세션 인증 없음
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        "로그인이 필요합니다."
                ));
    }

    /**
     * 사용자가 해당 KST 날짜에 이미 체크인했는지 확인합니다.
     *
     * @param userId 체크인 여부를 확인할 사용자 ID
     * @param checkInDate 확인할 KST 날짜
     * @throws BusinessException 이미 체크인한 날짜이면 409 CONCURRENT_MODIFICATION
     */
    private void ensureNotCheckedIn(Long userId, LocalDate checkInDate) {

        // 409 CONCURRENT_MODIFICATION 동시 처리 또는 이미 변경된 리소스
        if (serviceCheckInRepository.existsByUserIdAndCheckInDate(userId, checkInDate)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "CONCURRENT_MODIFICATION",
                    "오늘은 이미 체크인했습니다."
            );
        }
    }

    /**
     * 체크인을 귀속할 현재 그룹 시즌을 쓰기 잠금으로 조회합니다.
     *
     * 활성 시즌 또는 그룹 시즌이 없거나 그룹 시즌이 종료 상태이면
     * 체크인 처리를 거절합니다.
     *
     * @param membership 사용자의 활성 그룹 멤버십
     * @throws BusinessException 현재 그룹 시즌이 활성 상태가 아니면 409 QUEST_PERIOD_ENDED
     */
    private GroupSeason findActiveGroupSeason(GroupMembership membership) {
        var season = seasonQueryService.findCurrentSeasonForShare();

        GroupSeason groupSeason = groupSeasonRepository
                .findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        season.getId()
                )
                // 409 QUEST_PERIOD_ENDED 퀘스트 유효 기간 밖
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.CONFLICT,
                        "QUEST_PERIOD_ENDED",
                        "현재 그룹 시즌이 활성 상태가 아닙니다."
                ));

        if (!"ACTIVE".equals(groupSeason.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_PERIOD_ENDED",
                    "현재 그룹 시즌이 활성 상태가 아닙니다."
            );
        }

        return groupSeason;
    }

    /**
     * 사용자 잠금과 활성 멤버십·그룹 시즌·당일 중복 여부를 확인한 뒤,
     * 체크인 활동 기록과 체크인 기록을 같은 트랜잭션에서 저장합니다.
     *
     * @param userId 체크인을 요청한 사용자 ID
     * @return 체크인 처리 결과. 자동 완료한 퀘스트가 없으면 완료 ID 목록은 빈 배열입니다.
     */
    // 비관적 락, 상태 확인
    @Transactional
    public CheckInResponse createCheckIn(Long userId) {
        User user = findUserForUpdate(userId);
        GroupMembership membership = findActiveMembership(userId);
        GroupSeason groupSeason = findActiveGroupSeason(membership);

        // 시간 및 날짜 계산
        Instant checkedInAt = Instant.now(clock);
        LocalDate checkInDate = checkedInAt.atZone(KST).toLocalDate();
        ensureNotCheckedIn(userId, checkInDate);
        boolean isMorning = isMorningCheckIn(checkedInAt);

        // 사용자 활동 저장
        UserActivityEvent activityEvent = UserActivityEvent.create(
                user,
                "check_in",
                "service_check_in",
                userId,
                membership,
                groupSeason,
                checkInDate,
                checkedInAt,
                "check_in:" + userId + ":" + checkInDate,
                null
        );
        UserActivityEvent savedActivityEvent = activityEventRepository.save(activityEvent);

        // 실제 데이터 저장
        ServiceCheckIn checkIn = serviceCheckInRepository.save(
                ServiceCheckIn.create(
                        user,
                        savedActivityEvent,
                        checkInDate,
                        checkedInAt,
                        isMorning
                )
        );
        ArrayList<Long> completedQuestIds = new ArrayList<>();

        // 아침 체크
        if (isMorning) {
            Optional<QuestAssignment> morningQuest =
                    findCurrentMorningQuestAssignment(checkedInAt);

            //서버 주도 자동 퀘스트 완료
            if (morningQuest.isPresent()) {
                QuestCompletionResponse completion =
                        questCompletionService.completeServerEventAssignment(
                                userId,
                                morningQuest.get().getId()
                        );

                completedQuestIds.add(completion.getCompletionId());
            }
        }

        // 체크인응답 반환
        return new CheckInResponse(
                checkIn.getId(),
                checkIn.getCheckInDate(),
                checkIn.getCheckedInAt(),
                checkIn.getIsMorning(),
                completedQuestIds
        );
    }


}
