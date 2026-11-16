package com.team.sharedcreature.auth.infrastructure;

import com.team.sharedcreature.auth.domain.UserDailyActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;

/** 일별 인증 활동을 멤버십 범위별로 멱등 기록합니다. */
public interface UserDailyActivityRepository extends JpaRepository<UserDailyActivity, Long> {

    /** 같은 사용자·날짜·멤버십 활동을 한 행으로 upsert합니다. */
    @Modifying
    @Query(value = """
            INSERT INTO user_daily_activities (
                user_id
              , membership_id
              , group_id_snapshot
              , business_date
              , first_active_at
              , last_active_at
              , created_at
              , updated_at
            ) VALUES (
                :userId
              , :membershipId
              , :groupId
              , :businessDate
              , :now
              , :now
              , :now
              , :now
            )
            ON DUPLICATE KEY UPDATE
                last_active_at = GREATEST(last_active_at, VALUES(last_active_at))
              , updated_at     = GREATEST(updated_at, VALUES(updated_at))
            """, nativeQuery = true)
    int upsert(
            @Param("userId") Long userId,
            @Param("membershipId") Long membershipId,
            @Param("groupId") Long groupId,
            @Param("businessDate") LocalDate businessDate,
            @Param("now") Instant now
    );
}
