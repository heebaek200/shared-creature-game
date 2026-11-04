package com.team.sharedcreature.quest.infrastructure;

import com.team.sharedcreature.quest.domain.ServiceCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

/**
 * ServiceCheckIn 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface ServiceCheckInRepository extends JpaRepository<ServiceCheckIn, Long> {


    /**
     * 사용자가 지정한 날짜에 기상 체크인을 했는지 확인합니다.
     *
     * @param userId 체크인 여부를 확인할 사용자 ID
     * @param checkInDate 확인할 KST 날짜
     * @return 기상 체크인이 있으면 true, 없으면 false
     */
    boolean existsByUserIdAndCheckInDateAndIsMorningTrue(
            Long userId,
            LocalDate checkInDate
    );

}
