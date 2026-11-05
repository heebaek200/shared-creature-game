package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.quest.domain.QuestAssignment;
import com.team.sharedcreature.quest.domain.QuestCompletion;
import com.team.sharedcreature.quest.domain.QuestRewardLedger;
import com.team.sharedcreature.quest.infrastructure.*;
import com.team.sharedcreature.season.application.SeasonQueryService;
import com.team.sharedcreature.season.domain.CreatureExperienceLedger;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.SeasonMemberContribution;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 퀘스트 조회와 완료 처리를 담당합니다.
 */
@Service
@RequiredArgsConstructor
public class QuestCompletionService {

    /**
     * 사용자의 현재 시즌 기여도 행을 조회하고 갱신합니다.
     */
    private final SeasonMemberContributionRepository memberContributionRepository;

    /**
     * 현재 그룹 시즌의 점수 행을 조회하고 갱신합니다.
     */
    private final SeasonGroupScoreRepository groupScoreRepository;

    /**
     * 퀘스트 완료별 보상 지급 이력을 저장합니다.
     */
    private final QuestRewardLedgerRepository rewardLedgerRepository;

    /**
     * 현재 그룹 시즌의 크리처를 조회하고 경험치 변경을 반영합니다.
     */
    private final GroupCreatureRepository groupCreatureRepository;

    /**
     * 완료별 크리처 경험치 지급 이력을 저장합니다.
     */
    private final CreatureExperienceLedgerRepository creatureExperienceLedgerRepository;

    /**
     * 완료 기록에 연결할 사용자 정보를 조회합니다.
     */
    private final UserRepository userRepository;

    /**
     * 사용자의 그룹에 속한 현재 시즌 정보를 조회합니다.
     */
    private final GroupSeasonRepository groupSeasonRepository;

    /**
     * 현재 사용자의 활성 그룹 멤버십을 조회합니다.
     */
    private final GroupMembershipRepository membershipRepository;

    /**
     * 사용자의 체크인 기록을 조회합니다.
     */
    private final ServiceCheckInRepository serviceCheckInRepository;

    /**
     * 현재 활성 시즌을 조회합니다.
     */
    private final SeasonQueryService seasonQueryService;

    /**
     * 현재 진행 중인 일일·주간 퀘스트 기간을 조회합니다.
     */
    private final QuestPeriodRepository periodRepository;

    /**
     * 기간에 배정된 퀘스트와 Snapshot을 조회합니다.
     */
    private final QuestAssignmentRepository assignmentRepository;

    /**
     * 사용자별 퀘스트 완료 기록을 조회합니다.
     */
    private final QuestCompletionRepository completionRepository;

    /**
     * 현재 시각을 제공해 기간 경계 판정을 일정하게 처리합니다.
     */
    private final Clock clock;

    /**
     * 현재 활성 시즌에서 진행 중인 일일 또는 주간 퀘스트 배정을 조회합니다.
     *
     * @param periodType 조회할 기간 유형 (일일, 주간)
     * @return 슬롯 순서로 정렬된 현재 퀘스트 배정 목록
     */

    /**조회 하고자 하는 일일,주간 유형을 받고, 그에 맞는 퀘스트 목록을 리스트로 반환*/

    /**
     * 방어 코드 DAILY나 WEEKLY가 아닐경우 400 Bad Request 던짐
     */
    public List<QuestAssignment> findCurrentAssignments(String periodType) {
        if (periodType != null
                && !periodType.equals("DAILY")
                && !periodType.equals("WEEKLY")) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FIELD_VALUE",
                    "기간 유형이 올바르지 않습니다."
            );
        }

        /**null이 들어오면 DAILY WEEKLY 둘다 조회할 리스트로 생성*/
        Instant now = Instant.now(clock);
        Long seasonId = seasonQueryService.findCurrentSeason().getId();
        List<String> periodTypes = periodType == null
                ? List.of("DAILY", "WEEKLY")
                : List.of(periodType);

        /**반복문으로 퀘스트 수집*/
        List<QuestAssignment> assignments = new ArrayList<>();

        for (String type : periodTypes) {
            periodRepository.findCurrentBySeasonAndType(seasonId, type, now)
                    .ifPresent(period ->
                            assignments.addAll(
                                    assignmentRepository.findAllWithSnapshotByPeriodId(period.getId())
                            )
                    );
        }

        return assignments;
    }

    /**
     * 현재 퀘스트 배정에 로그인한 사용자의 완료 기록을 연결합니다.
     * <p>
     * 배정 ID 목록으로 해당 사용자의 완료 기록만 조회한 뒤,
     * 각 완료 기록을 퀘스트 배정 ID를 키로 하는 Map에 저장합니다.</p>
     *
     * @param userId      로그인한 사용자 ID
     * @param assignments 현재 기간에 배정된 퀘스트 목록
     * @return 배정 ID별 완료 기록. 완료 기록이 없으면 해당 배정 ID는 Map에 없습니다.
     */
    public Map<Long, QuestCompletion> findCompletionsByAssignmentId(
            Long userId,
            List<QuestAssignment> assignments
    ) {
        /**방어 코드 불필요한 assignments.isEmpty()일때, 데이터베이스 조회 쿼리를 날리지 않고 곧바로 빈 맵을 반환*/
        Map<Long, QuestCompletion> completionsByAssignmentId = new HashMap<>();
        if (assignments.isEmpty()) {
            return completionsByAssignmentId;
        }

        /**할당 ID 추출*/
        List<Long> assignmentIds = new ArrayList<>();
        for (QuestAssignment assignment : assignments) {
            assignmentIds.add(assignment.getId());
        }

        /**사용자가 가진 완료 이력 중 해당 assignmentIds에 포함되는 데이터들을 단 한 번의 쿼리로 일괄 조회*/
        List<QuestCompletion> completions =
                completionRepository.findAllByUserIdAndQuestAssignmentIdIn(userId, assignmentIds);

        /**조회된 리스트 형태의 완료 이력을 순회하며, 각 완료 데이터가 어떤 퀘스트에 속하는지 키값으로 매핑*/
        for (QuestCompletion completion : completions) {
            Long assignmentId = completion.getQuestAssignment().getId();
            completionsByAssignmentId.put(assignmentId, completion);
        }

        return completionsByAssignmentId;
    }

    /**
     * 현재 사용자의 퀘스트 배정과 완료 상태를 조회해 응답 DTO로 변환합니다.
     * <p>
     * 먼저 활성 그룹 멤버십이 있는지 확인합니다. 이후 현재 기간의 배정을 조회하고,
     * 로그인한 사용자 ID로 완료 기록을 조회해 각 배정에 연결합니다.
     *
     * @param userId     로그인한 사용자 ID
     * @param periodType 조회할 기간 유형 DAILY, WEEKLY,
     *                   전체 조회를 위한 null
     * @return 배정 퀘스트와 사용자의 완료 상태 목록
     */
    @Transactional(readOnly = true)
    public List<CurrentQuestResponse> listCurrentQuests(Long userId, String periodType) {

        // 시즌 참여 확인 403  ROLE_FORBIDDEN  요청자 권한 부족
        GroupMembership membership = membershipRepository
                .findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));

        // 404  RESOURCE_NOT_FOUND  없거나 요청자에게 노출하지 않는 리소스
        Long seasonId = seasonQueryService.findCurrentSeason().getId();
        GroupSeason groupSeason = groupSeasonRepository
                .findSnapshotByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        seasonId
                )
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "현재 그룹의 시즌 참여 정보를 찾을 수 없습니다."
                ));

        /**퀘스트 할당과 완료 이력 조회*/
        List<QuestAssignment> assignments = findCurrentAssignments(periodType);
        Map<Long, QuestCompletion> completionsByAssignmentId =
                findCompletionsByAssignmentId(userId, assignments);


        /**
         * 퀘스트 하나씩 조회해서 할당 만들어둔MAP에서 퀘스트 완료 확인
         * 완료 여부에따라 완료시간 완료이력ID를 동적으로 설정
         * CurrentQuestResponse에 감싸서 최종 응답리스트에 추가
         * */
        List<CurrentQuestResponse> responses = new ArrayList<>();

        // 기본 초기화 및 완료 여부 파악
        for (QuestAssignment assignment : assignments) {
            QuestCompletion completion = completionsByAssignmentId.get(assignment.getId());
            boolean completed = completion != null;

            String templateCode = assignment.getQuestTemplate().getCode();
            int progressTarget = 1;
            int currentProgress = completed ? 1 : 0;

            // 아침 체크인 연동 일일 퀘스트 ("D-M-01")
            if ("D-M-01".equals(templateCode) && !completed) {
                boolean checkedInThisMorning =
                        serviceCheckInRepository.existsByUserIdAndCheckInDateAndIsMorningTrue(
                                userId,
                                assignment.getQuestPeriod().getPeriodDate()
                        );

                if (checkedInThisMorning) {
                    currentProgress = 1;
                }
                // W-L-02, W-M-02, W-H-02가 각각 주간 일일 퀘스트 완료 3·7·14회를 진행도로 표시
            } else if ("W-L-02".equals(templateCode)
                    || "W-M-02".equals(templateCode)
                    || "W-H-02".equals(templateCode)) {
                progressTarget = "W-L-02".equals(templateCode) ? 3
                        : "W-M-02".equals(templateCode) ? 7
                        : 14;

                // 이미 완료된 퀘스트 처리
                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    // 미완료 퀘스트의 기간별 누적 완료 횟수 집계
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            completionRepository.countDailyCompletionsInDateRange(
                                    userId,
                                    membership.getId(),
                                    groupSeason.getId(),
                                    weekStart,
                                    weekEndExclusive
                            ),
                            progressTarget
                    );
                }
                // W-L-03과 W-M-03은 서로 다른 날짜에 일일 퀘스트를 완료한 일수를 표시
                // 완료 횟수가 아닌 완료한 날짜 수(Days) 집계
            } else if ("W-L-03".equals(templateCode)
                    || "W-M-03".equals(templateCode)) {
                progressTarget = "W-L-03".equals(templateCode) ? 2 : 4;

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            completionRepository.countDailyCompletionDaysInDateRange(
                                    userId,
                                    membership.getId(),
                                    groupSeason.getId(),
                                    weekStart,
                                    weekEndExclusive
                            ),
                            progressTarget
                    );
                }
                // W-L-04는 하 난이도 2회, W-M-04는 중 난이도 3회를 기준으로 진행도를 표시
            } else if ("W-L-04".equals(templateCode)
                    || "W-M-04".equals(templateCode)) {
                boolean lowDifficulty = "W-L-04".equals(templateCode);
                progressTarget = lowDifficulty ? 2 : 3;
                String difficulty = lowDifficulty ? "LOW" : "MEDIUM";

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            completionRepository.countDailyCompletionsByDifficultyInDateRange(
                                    userId,
                                    membership.getId(),
                                    groupSeason.getId(),
                                    difficulty,
                                    weekStart,
                                    weekEndExclusive
                            ),
                            progressTarget
                    );
                }

                //  "W-L-05"진행도에 "SELF_CHECK" 일일 퀘스트 수 표시
            } else if ("W-L-05".equals(templateCode)) {
                progressTarget = 2;

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            completionRepository
                                    .countDailyCompletionsByVerificationTypeInDateRange(
                                            userId,
                                            membership.getId(),
                                            groupSeason.getId(),
                                            "SELF_CHECK",
                                            weekStart,
                                            weekEndExclusive
                                    ),
                            progressTarget
                    );
                }
                // "W-H-03" 일일퀘스트 3개를 완료해야 +1일 총 3일 되면 완료
            } else if ("W-H-03".equals(templateCode)) {
                progressTarget = 3;

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            completionRepository
                                    .countFullyCompletedDailyQuestDaysInDateRange(
                                            userId,
                                            membership.getId(),
                                            groupSeason.getId(),
                                            weekStart,
                                            weekEndExclusive
                                    ),
                            progressTarget
                    );
                }

                // 주간 일반 체크인 횟수 달성 퀘스트 ("W-L-01", "W-M-01", "W-H-01")
            } else if ("W-L-01".equals(templateCode)
                    || "W-M-01".equals(templateCode)
                    || "W-H-01".equals(templateCode)) {
                progressTarget = "W-L-01".equals(templateCode) ? 3
                        : "W-M-01".equals(templateCode) ? 5
                        : 7;

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            serviceCheckInRepository.countCheckInsInDateRange(
                                    userId,
                                    weekStart,
                                    weekEndExclusive
                            ),
                            progressTarget
                    );
                }
                // 주간 아침 체크인 횟수 달성 퀘스트 ("W-L-06", "W-H-04")
            } else if ("W-L-06".equals(templateCode)
                    || "W-H-04".equals(templateCode)) {
                progressTarget = "W-L-06".equals(templateCode) ? 2 : 5;

                if (completed) {
                    currentProgress = progressTarget;
                } else {
                    var weekStart = assignment.getQuestPeriod().getPeriodDate();
                    var weekEndExclusive = weekStart.plusWeeks(1);

                    currentProgress = (int) Math.min(
                            serviceCheckInRepository.countMorningCheckInsInDateRange(
                                    userId,
                                    weekStart,
                                    weekEndExclusive
                            ),
                            progressTarget
                    );
                }
            }

            //응답 객체 조립
            CurrentQuestResponse.ProgressResponse progress =
                    new CurrentQuestResponse.ProgressResponse(
                            currentProgress,
                            progressTarget
                    );

            responses.add(new CurrentQuestResponse(
                    assignment.getId(),
                    assignment.getQuestPeriod().getId(),
                    assignment.getQuestPeriod().getPeriodType(),
                    assignment.getQuestPeriod().getStartAt(),
                    assignment.getQuestPeriod().getEndAt(),
                    assignment.getSlotNumber(),
                    assignment.getQuestTemplate().getCode(),
                    assignment.getTitleSnapshot(),
                    assignment.getDescriptionSnapshot(),
                    assignment.getDifficultySnapshot(),
                    assignment.getVerificationTypeSnapshot(),
                    assignment.getCreatureExperienceSnapshot(),
                    assignment.getContributionScoreSnapshot(),
                    completed,
                    completed ? completion.getCompletedAt() : null,
                    completed ? completion.getId() : null,
                    progress,
                    null
            ));
        }

        return responses;
    }

    /**
     * 완료 요청 대상 배정을 조회하고 직접 완료 가능 여부를 검사합니다.
     *
     * 먼저 사용자의 활성 멤버십을 확인합니다. 배정이 현재 활성 시즌에 속하고
     * 현재 시각이 퀘스트 기간 안에 있는지도 검사합니다. 직접 완료는
     * SELF_CHECK 유형에만 허용합니다.
     *
     * @param userId 완료를 요청한 사용자 ID
     * @param assignmentId 완료 요청 대상 퀘스트 배정 ID
     * @return 검증을 통과한 퀘스트 배정
     */

    /**
     * 403	ROLE_FORBIDDEN	요청자 권한 부족
     */
    @Transactional(readOnly = true)
    public QuestAssignment findCompletableAssignment(Long userId,
                                                     Long assignmentId,
                                                     String verificationType
    ) {
        membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));

        /**404	RESOURCE_NOT_FOUND	없거나 요청자에게 노출하지 않는 리소스*/
        QuestAssignment assignment = assignmentRepository.findWithSnapshotById(assignmentId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "퀘스트 배정을 찾을 수 없습니다."
                ));

        Instant now = Instant.now(clock);
        Long currentSeasonId = seasonQueryService.findCurrentSeason().getId();

        /**시즌 일치 여부*/
        boolean currentSeason = assignment.getQuestPeriod().getSeason().getId()
                .equals(currentSeasonId);

        /**활성 기간 확인*/
        boolean activePeriod = "ACTIVE".equals(assignment.getQuestPeriod().getStatus())
                && !now.isBefore(assignment.getQuestPeriod().getStartAt())
                && now.isBefore(assignment.getQuestPeriod().getEndAt());

        /**409	QUEST_PERIOD_ENDED	퀘스트 유효 기간 밖*/
        if (!currentSeason || !activePeriod) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_PERIOD_ENDED",
                    "퀘스트 유효 기간이 아닙니다."
            );
        }

        /**409  QUEST_COMPLETION_NOT_ALLOWED  해당 판정 유형 직접 완료 불가*/
        if (!verificationType.equals(assignment.getVerificationTypeSnapshot())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_COMPLETION_NOT_ALLOWED",
                    "이 퀘스트는 직접 완료할 수 없습니다."
            );
        }

        return assignment;
    }

    /**
     * 사용자의 현재 그룹 시즌에 퀘스트 완료 기록을 가져오거나 새로 저장합니다.
     *
     * 기존 완료 기록이 있으면 재사용합니다. 없으면 활성 멤버십과 그룹 시즌을 확인한 뒤
     * 배정 Snapshot에 대한 완료 기록을 생성해 저장합니다.
     *
     * @param userId 완료를 요청한 사용자 ID
     * @param assignment 검증을 통과한 퀘스트 배정
     * @return 기존 또는 새로 저장한 완료 기록
     */

    /**
     * 중복 방지
     */
    private QuestCompletion getOrCreateCompletion(Long userId, QuestAssignment assignment) {
        Map<Long, QuestCompletion> existingCompletions =
                findCompletionsByAssignmentId(userId, List.of(assignment));

        QuestCompletion existingCompletion = existingCompletions.get(assignment.getId());
        if (existingCompletion != null) {
            return existingCompletion;
        }

        /**그룹 소속 검증 403 ROLE_FORBIDDEN	요청자 권한 부족*/
        var membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));

        /**그룹 시즌 참여 정보 검증 404 RESOURCE_NOT_FOUND 없거나 요청자에게 노출하지 않는 리소스*/
        Long seasonId = assignment.getQuestPeriod().getSeason().getId();
        var groupSeason = groupSeasonRepository.findSnapshotByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        seasonId
                )
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "현재 그룹의 시즌 참여 정보를 찾을 수 없습니다."
                ));

        /**그룹 시즌 활성 정보 검증 409 QUEST_PERIOD_ENDED 퀘스트 유효 기간 밖*/
        if (!"ACTIVE".equals(groupSeason.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_PERIOD_ENDED",
                    "그룹의 현재 시즌이 활성 상태가 아닙니다."
            );
        }

        /**유저 확인 및 완료이력 생성 401	AUTH_REQUIRED 세션 인증 없음*/
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        "로그인이 필요합니다."
                ));

        QuestCompletion completion = QuestCompletion.create(
                user,
                assignment,
                groupSeason,
                membership,
                Instant.now(clock)
        );

        return completionRepository.save(completion);
    }

    /**
     * 현재 그룹 시즌의 사용자 기여도 행을 조회합니다.
     * <p>
     * 기여도 행이 아직 없으면 0점으로 생성해 저장합니다.
     *
     * @param groupSeason 기여도가 속할 그룹 시즌
     * @param user        기여도 소유 사용자
     * @param membership  현재 멤버십
     * @param now         현재 시각
     * @return 기존 또는 새로 저장한 기여도 행
     */
    private SeasonMemberContribution getOrCreateMemberContribution(
            GroupSeason groupSeason,
            User user,
            GroupMembership membership,
            Instant now
    ) {
        /**기존 기여도 조회*/
        SeasonMemberContribution contribution =
                memberContributionRepository.findByGroupSeasonIdAndUserId(
                        groupSeason.getId(),
                        user.getId()
                ).orElse(null);

        if (contribution != null) {
            return contribution;
        }

        /**신규 기여도 객체 생성및 초기화*/
        SeasonMemberContribution newContribution =
                SeasonMemberContribution.zero(groupSeason, user, membership, now);

        return memberContributionRepository.save(newContribution);
    }

    /**
     * 현재 그룹 시즌의 점수 행을 조회합니다.
     * <p>
     * 그룹 시즌 생성 과정에서 점수 행도 함께 만들어져야 하므로,
     * 행이 없으면 업무 오류 대신 서버 내부 상태 오류로 처리합니다.
     *
     * @param groupSeasonId 조회할 그룹 시즌 ID
     * @return 해당 그룹 시즌의 점수 행
     */
    private SeasonGroupScore findGroupScore(Long groupSeasonId) {
        return groupScoreRepository.findByGroupSeasonId(groupSeasonId)
                .orElseThrow(() -> new IllegalStateException(
                        "초기 데이터 누락 groupSeasonId = " + groupSeasonId
                ));
    }

    /**
     * SELF_CHECK 퀘스트 완료를 확정하고 경험치·기여도 보상을 반영합니다.
     *
     * 현재 시즌과 그룹 시즌을 확인한 뒤, 기존 완료 기록이 있으면 재사용합니다.
     * 새 완료라면 완료 기록, 보상 원장, 점수와 크리처 경험치를 한 트랜잭션에서 저장합니다.
     *
     * @param userId 완료를 요청한 사용자 ID
     * @param assignmentId 완료할 퀘스트 배정 ID
     * @return 완료 및 보상 반영 결과
     */

    /**
     * 동시성 락
     */
    @Transactional
    public QuestCompletionResponse completeQuestAssignment(Long userId, Long assignmentId) {
        seasonQueryService.findCurrentSeasonForShare();

        /**401	AUTH_REQUIRED	세션 인증 없음*/
        var user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다."));

        /**403	ROLE_FORBIDDEN	요청자 권한 부족*/
        var membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "요청 권한이 없습니다."));

        QuestAssignment assignment = findCompletableAssignment(userId, assignmentId, "SELF_CHECK");

        /**404	RESOURCE_NOT_FOUND	없거나 요청자에게 노출하지 않는 리소스*/
        var groupSeason = groupSeasonRepository.findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        assignment.getQuestPeriod().getSeason().getId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "그룹 시즌을 찾을 수 없습니다."));

        /**409	QUEST_PERIOD_ENDED	퀘스트 유효 기간 밖*/
        if (!"ACTIVE".equals(groupSeason.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "QUEST_PERIOD_ENDED", "그룹 시즌이 활성 상태가 아닙니다.");
        }

        return completeAndReward(user, membership, groupSeason, assignment);
    }

    // << QuestCompletionService.java 471줄 위치에 삽입

    /**
     * 체크인 기록으로 조건을 충족한 SERVER_EVENT 퀘스트를 완료합니다.
     * <p>
     * 현재 시즌·활성 멤버십·퀘스트 기간을 확인하고, 해당 KST 날짜에
     * 기상 체크인이 실제 기록된 D-M-01 배정만 공통 보상 처리로 전달합니다.
     *
     * @param userId       체크인한 사용자 ID
     * @param assignmentId 완료할 퀘스트 배정 ID
     * @return 완료 및 Snapshot 기준 보상 반영 결과
     */
    @Transactional
    public QuestCompletionResponse completeServerEventAssignment(
            Long userId,
            Long assignmentId
    ) {
        seasonQueryService.findCurrentSeasonForShare();

        //401 AUTH_REQUIRED	세션 인증 없음
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        "로그인이 필요합니다."
                ));

        //403	ROLE_FORBIDDEN	요청자 권한 부족
        GroupMembership membership = membershipRepository
                .findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));

        QuestAssignment assignment =
                findCompletableAssignment(userId, assignmentId, "SERVER_EVENT");

        boolean morningQuest = "DAILY".equals(
                assignment.getQuestPeriod().getPeriodType()
        ) && "D-M-01".equals(assignment.getQuestTemplate().getCode());

        boolean morningCheckInExists =
                serviceCheckInRepository.existsByUserIdAndCheckInDateAndIsMorningTrue(
                        userId,
                        assignment.getQuestPeriod().getPeriodDate()
                );

        // 409 QUEST_COMPLETION_NOT_ALLOWED	해당 판정 유형 직접 완료 불가
        if (!morningQuest || !morningCheckInExists) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_COMPLETION_NOT_ALLOWED",
                    "기상 체크인으로 완료할 수 있는 퀘스트가 아닙니다."
            );
        }

        // 404	RESOURCE_NOT_FOUND	없거나 요청자에게 노출하지 않는 리소스
        GroupSeason groupSeason = groupSeasonRepository
                .findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        assignment.getQuestPeriod().getSeason().getId()
                )
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "그룹 시즌을 찾을 수 없습니다."
                ));

        if (!"ACTIVE".equals(groupSeason.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_PERIOD_ENDED",
                    "그룹 시즌이 활성 상태가 아닙니다."
            );
        }

        return completeAndReward(user, membership, groupSeason, assignment);
    }

    /**
     * 체크인으로 진행되는 주간 집계 퀘스트가 목표에 도달하면 완료와 보상을 반영합니다.
     *
     * @param userId       체크인한 사용자 ID
     * @param assignmentId 확인할 주간 퀘스트 배정 ID
     * @return 새로 완료한 경우 완료 결과, 아직 완료할 수 없으면 빈 Optional
     */
    @Transactional
    public Optional<QuestCompletionResponse> completeCheckInAggregateIfEligible(
            Long userId,
            Long assignmentId
    ) {
        // 동시성 제어
        seasonQueryService.findCurrentSeasonForShare();
        // 401  AUTH_REQUIRED  세션 인증 없음
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        "로그인이 필요합니다."
                ));

        // 403  ROLE_FORBIDDEN  요청자 권한 부족
        GroupMembership membership = membershipRepository
                .findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "ROLE_FORBIDDEN",
                        "요청 권한이 없습니다."
                ));

        // 퀘스트 코드별 목표치 및 조건 확인
        QuestAssignment assignment =
                findCompletableAssignment(userId, assignmentId, "AGGREGATE");
        String templateCode = assignment.getQuestTemplate().getCode();
        int target;
        boolean morningOnly;

        if ("W-L-01".equals(templateCode)) {
            target = 3;
            morningOnly = false;
        } else if ("W-L-06".equals(templateCode)) {
            target = 2;
            morningOnly = true;
        } else if ("W-M-01".equals(templateCode)) {
            target = 5;
            morningOnly = false;
        } else if ("W-H-01".equals(templateCode)) {
            target = 7;
            morningOnly = false;
        } else if ("W-H-04".equals(templateCode)) {
            target = 5;
            morningOnly = true;
        } else {
            return Optional.empty();
        }

        // 실제 출석일 검증
        if (!"WEEKLY".equals(assignment.getQuestPeriod().getPeriodType())) {
            return Optional.empty();
        }
        LocalDate weekStart = assignment.getQuestPeriod().getPeriodDate();
        LocalDate weekEndExclusive = weekStart.plusWeeks(1);

        long current = morningOnly
                ? serviceCheckInRepository.countMorningCheckInsInDateRange(
                userId,
                weekStart,
                weekEndExclusive
        )
                : serviceCheckInRepository.countCheckInsInDateRange(
                userId,
                weekStart,
                weekEndExclusive
        );

        if (current < target) {
            return Optional.empty();
        }

        // 404  RESOURCE_NOT_FOUND  없거나 요청자에게 노출하지 않는 리소스
        // 중복 완료 방지
        GroupSeason groupSeason = groupSeasonRepository
                .findForUpdateByGroupIdAndSeasonId(
                        membership.getGroup().getId(),
                        assignment.getQuestPeriod().getSeason().getId()
                )
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "그룹 시즌을 찾을 수 없습니다."
                ));
        if (!"ACTIVE".equals(groupSeason.getStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "QUEST_PERIOD_ENDED",
                    "그룹 시즌이 활성 상태가 아닙니다."
            );
        }
        Map<Long, QuestCompletion> existingCompletions =
                findCompletionsByAssignmentId(userId, List.of(assignment));

        if (existingCompletions.containsKey(assignmentId)) {
            return Optional.empty();
        }

        // 조건 충족 시 보상 지급 실행
        return Optional.of(
                completeAndReward(user, membership, groupSeason, assignment)
        );
    }

    /**
     * 퀘스트 배정 Snapshot의 보상값을 사용해 완료와 보상을 반영합니다.
     * <p>
     * 이미 완료된 배정이면 기존 결과를 반환합니다. 새 완료라면 완료 기록,
     * 크리처 경험치, 개인 기여도, 그룹 점수와 보상 원장을 한 트랜잭션에서 저장합니다.
     *
     * @param user        완료한 사용자
     * @param membership  보상 반영 시점의 활성 멤버십
     * @param groupSeason 보상이 귀속될 활성 그룹 시즌
     * @param assignment  완료할 퀘스트 배정
     * @return 완료 및 보상 반영 결과
     */
    private QuestCompletionResponse completeAndReward(
            User user,
            GroupMembership membership,
            GroupSeason groupSeason,
            QuestAssignment assignment
    ) {
        Long userId = user.getId();

        // 중복 완료 방지
        Map<Long, QuestCompletion> existingCompletions =
                findCompletionsByAssignmentId(userId, List.of(assignment));
        QuestCompletion existing = existingCompletions.get(assignment.getId());

        if (existing != null) {
            return buildExistingCompletionResponse(existing);
        }

        // 자원 조회 및 동시성 락
        // 404 RESOURCE_NOT_FOUND 없거나 요청자에게 노출하지 않는 리소스
        Instant now = Instant.now(clock);
        var creature = groupCreatureRepository
                .findForUpdateByGroupSeasonId(groupSeason.getId())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND",
                        "그룹 크리처를 찾을 수 없습니다."
                ));
        var contribution = getOrCreateMemberContribution(
                groupSeason,
                user,
                membership,
                now
        );
        var groupScore = findGroupScore(groupSeason.getId());

        // 스냅샷 기반 보상 추출 및 완료 처리
        long experience = assignment.getCreatureExperienceSnapshot();
        long contributionAmount = assignment.getContributionScoreSnapshot();

        QuestCompletion completion = completionRepository.save(
                QuestCompletion.create(
                        user,
                        assignment,
                        groupSeason,
                        membership,
                        now
                )
        );

        // 상태 갱신 (크리처, 기여도, 그룹 점수)
        creature.addExperience(experience, now);
        contribution.addContribution(contributionAmount, membership, now);
        groupScore.addScore(contributionAmount, now);

        // 최종 응답 반환
        creatureExperienceLedgerRepository.save(
                CreatureExperienceLedger.grant(
                        creature,
                        completion,
                        experience,
                        creature.getTotalExperience(),
                        now
                )
        );

        rewardLedgerRepository.saveAll(List.of(
                QuestRewardLedger.create(
                        completion,
                        "CREATURE_EXPERIENCE",
                        experience,
                        creature,
                        null,
                        null,
                        now
                ),
                QuestRewardLedger.create(
                        completion,
                        "CONTRIBUTION",
                        contributionAmount,
                        null,
                        contribution,
                        groupScore,
                        now
                )
        ));
        // 달성 여부를 검사 이후 집계 퀘스트 처리
        if ("DAILY".equals(assignment.getQuestPeriod().getPeriodType())) {
            completeDailyCompletionAggregates(user, membership, groupSeason);
        }
        return new QuestCompletionResponse(
                completion.getId(),
                assignment.getId(),
                userId,
                completion.getCompletedAt(),
                experience,
                contributionAmount,
                creature.getTotalExperience(),
                creature.getGrowthStage(),
                groupScore.getScore()
        );
    }

    /**
     * 일일 퀘스트 완료 수를 기준으로 주간 집계 퀘스트를 자동 완료합니다.
     *
     *
     * @param user 완료한 사용자
     * @param membership 현재 활성 멤버십
     * @param groupSeason 완료 기록이 귀속된 그룹 시즌
     */
    private void completeDailyCompletionAggregates(
            User user,
            GroupMembership membership,
            GroupSeason groupSeason
    ) {
        List<QuestAssignment> weeklyAssignments = findCurrentAssignments("WEEKLY");

        // 현재 주간 퀘스트 조회 및 집계형 필터링
        for (QuestAssignment weeklyAssignment : weeklyAssignments) {
            if (!"AGGREGATE".equals(weeklyAssignment.getVerificationTypeSnapshot())) {
                continue;
            }

            String templateCode = weeklyAssignment.getQuestTemplate().getCode();
            int target;

            // 코드별 목표치 확인
            if ("W-L-02".equals(templateCode)) {
                target = 3;
            } else if ("W-M-02".equals(templateCode)) {
                target = 7;
            } else if ("W-H-02".equals(templateCode)) {
                target = 14;
            } else {
                continue;
            }

            // 기간별 누적 완료 횟수 집계 및 조건 검사
            var weekStart = weeklyAssignment.getQuestPeriod().getPeriodDate();
            var weekEndExclusive = weekStart.plusWeeks(1);

            long current = completionRepository.countDailyCompletionsInDateRange(
                    user.getId(),
                    membership.getId(),
                    groupSeason.getId(),
                    weekStart,
                    weekEndExclusive
            );

            if (current < target) {
                continue;
            }

            // 중복 완료 방지 및 자동 보상 지급
            Map<Long, QuestCompletion> existingCompletions =
                    findCompletionsByAssignmentId(
                            user.getId(),
                            List.of(weeklyAssignment)
                    );

            if (existingCompletions.containsKey(weeklyAssignment.getId())) {
                continue;
            }

            completeAndReward(user, membership, groupSeason, weeklyAssignment);
        }
    }

    /**
     * 이미 완료된 퀘스트 조회 시: 데이터베이스를 다시 변경(INSERT/UPDATE)하지 않고,
     * 과거에 저장해 둔 보상 (rewardLedgers)과 경험치를 역으로 조회하여
     * 당시 받았던 정확한 보상 값과 현재 크리처/그룹 상태를 조합해 응답을 만들어 줍니다.
     * 버튼을 두번 클릭했을때 방어하는 코드
     *
     * @param completion 기존 완료 기록
     * @return 기존 지급량과 현재 시즌 상태를 담은 완료 응답
     */
    private QuestCompletionResponse buildExistingCompletionResponse(QuestCompletion completion) {
        long experienceAwarded = 0L;
        long contributionAwarded = 0L;

        List<QuestRewardLedger> rewardLedgers =
                rewardLedgerRepository.findAllByQuestCompletionId(completion.getId());

        // 크리처 경험치 변동 이력, 지금 상태 조회
        for (QuestRewardLedger ledger : rewardLedgers) {
            if ("CREATURE_EXPERIENCE".equals(ledger.getRewardType())) {
                experienceAwarded = ledger.getAmount();
            } else if ("CONTRIBUTION".equals(ledger.getRewardType())) {
                contributionAwarded = ledger.getAmount();
            }
        }

        var experienceLedger = creatureExperienceLedgerRepository
                .findByQuestCompletionId(completion.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Creature experience ledger is missing. completionId=" + completion.getId()));

        var creature = groupCreatureRepository
                .findSnapshotByGroupSeasonId(completion.getGroupSeason().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Group creature is missing. groupSeasonId=" + completion.getGroupSeason().getId()));

        var groupScore = findGroupScore(completion.getGroupSeason().getId());

        // DTO 조립
        return new QuestCompletionResponse(
                completion.getId(),
                completion.getQuestAssignment().getId(),
                completion.getUser().getId(),
                completion.getCompletedAt(),
                experienceAwarded,
                contributionAwarded,
                experienceLedger.getBalanceAfter(),
                creature.getGrowthStage(),
                groupScore.getScore()
        );
    }

}
