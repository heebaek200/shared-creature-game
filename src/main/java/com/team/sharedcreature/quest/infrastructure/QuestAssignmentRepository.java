package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.QuestAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * QuestAssignment 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface QuestAssignmentRepository extends JpaRepository<QuestAssignment, Long> {

    /**
     * 퀘스트 배정 정보를 조회하고 저장합니다.
     * 한 기간에 배정된 퀘스트를 슬롯 순서대로 조회합니다.
     * 응답에 필요한 기간, 템플릿, 배정 시점의 Snapshot을 함께 가져옵니다.
     *
     * @param periodId 조회할 퀘스트 기간 ID
     * @return 슬롯 번호 오름차순으로 정렬된 퀘스트 배정 목록
     */
    @Query("""
            SELECT a
              FROM QuestAssignment a
              JOIN FETCH a.questPeriod p
              JOIN FETCH a.questTemplate
              JOIN FETCH a.questTemplateVersion
             WHERE p.id = :periodId
             ORDER BY a.slotNumber
            """)
    List<QuestAssignment> findAllWithSnapshotByPeriodId(@Param("periodId") Long periodId);

    /**
     * ID로 퀘스트 배정과 완료 판정에 필요한 Snapshot 정보를 조회합니다.
     *
     * @param assignmentId 조회할 퀘스트 배정 ID
     * @return 배정을 찾으면 해당 배정, 없으면 빈 Optional
     */
    @Query("""
        SELECT a
          FROM QuestAssignment a
          JOIN FETCH a.questPeriod p
          JOIN FETCH p.season
          JOIN FETCH a.questTemplate
          JOIN FETCH a.questTemplateVersion
         WHERE a.id = :assignmentId
        """)
    Optional<QuestAssignment> findWithSnapshotById(
            @Param("assignmentId") Long assignmentId
    );
}