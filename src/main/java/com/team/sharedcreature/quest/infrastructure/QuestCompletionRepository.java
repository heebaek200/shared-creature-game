package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * QuestCompletion 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestCompletionRepository extends JpaRepository<QuestCompletion, Long> {

    /**
     * 지정한 사용자가 지정된 퀘스트 배정들을 완료했는지 조회합니다.
     *
     * @param userId 완료 기록을 조회할 사용자 ID
     * @param assignmentIds 완료 상태를 확인할 퀘스트 배정 ID 목록
     * @return 해당 사용자의 완료 기록 목록
     */
    List<QuestCompletion> findAllByUserIdAndQuestAssignmentIdIn(
            Long userId,
            List<Long> assignmentIds
    );

    /**
     * 지정한 주간 날짜 구간에서 현재 멤버십·그룹 시즌에 속한
     * 일일 퀘스트 완료 횟수를 셉니다.
     *
     * @param userId 완료 횟수를 집계할 사용자 ID
     * @param membershipId 현재 활성 멤버십 ID
     * @param groupSeasonId 현재 그룹 시즌 ID
     * @param startDate 집계 시작 날짜(포함)
     * @param endDateExclusive 집계 종료 날짜(미포함)
     * @return 구간 안에서 완료한 일일 퀘스트 수
     */
    @Query("""
            SELECT COUNT(c)
            FROM QuestCompletion c
            JOIN c.questAssignment a
            JOIN a.questPeriod p
            WHERE c.user.id = :userId
            AND c.membership.id = :membershipId
            AND c.groupSeason.id = :groupSeasonId
            AND p.periodType = 'DAILY'
            AND p.periodDate >= :startDate
            AND p.periodDate < :endDateExclusive
            """)
    long countDailyCompletionsInDateRange(
            @Param("userId") Long userId,
            @Param("membershipId") Long membershipId,
            @Param("groupSeasonId") Long groupSeasonId,
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive
    );

}
