package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.ServiceCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * ServiceCheckIn 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface ServiceCheckInRepository extends JpaRepository<ServiceCheckIn, Long> {


    /**
     * 사용자가 지정한 날짜에 기상 체크인을 했는지 확인합니다.
     *
     * @param userId      체크인 여부를 확인할 사용자 ID
     * @param checkInDate 확인할 KST 날짜
     * @return 기상 체크인이 있으면 true, 없으면 false
     */
    boolean existsByUserIdAndCheckInDateAndIsMorningTrue(
            Long userId,
            LocalDate checkInDate
    );

    /**
     * 사용자가 지정한 날짜에 이미 체크인했는지 확인합니다.
     *
     * @param userId      체크인 여부를 확인할 사용자 ID
     * @param checkInDate 확인할 KST 날짜
     * @return 해당 날짜 체크인 기록이 있으면 true
     */
    boolean existsByUserIdAndCheckInDate(Long userId, LocalDate checkInDate);

    /**
     * 지정한 KST 날짜 구간의 서비스 체크인 일수를 셉니다.
     * 시작 날짜는 포함하고 종료 날짜는 제외합니다.
     *
     * @param userId           체크인 일수를 집계할 사용자 ID
     * @param startDate        집계 구간 시작 날짜
     * @param endDateExclusive 집계에 포함하지 않을 종료 날짜
     * @return 구간 내 체크인 횟수
     */
    @Query("""
            SELECT COUNT(c)
            FROM ServiceCheckIn c
            WHERE c.user.id = :userId
            AND c.checkInDate >= :startDate
            AND c.checkInDate < :endDateExclusive
            """)
    long countCheckInsInDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive
    );

    /**
     * 지정한 KST 날짜 구간의 오전 체크인 일수를 셉니다.
     * 시작 날짜는 포함하고 종료 날짜는 제외합니다.
     *
     * @param userId           오전 체크인 일수를 집계할 사용자 ID
     * @param startDate        집계 구간 시작 날짜
     * @param endDateExclusive 집계에 포함하지 않을 종료 날짜
     * @return 구간 내 오전 체크인 횟수
     */
    @Query("""
            SELECT COUNT(c)
            FROM ServiceCheckIn c
            WHERE c.user.id = :userId
            AND c.checkInDate >= :startDate
            AND c.checkInDate < :endDateExclusive
            AND c.isMorning = true
            """)
    long countMorningCheckInsInDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive
    );
}
