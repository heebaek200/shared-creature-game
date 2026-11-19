package com.team.sharedcreature.admin.infrastructure;

import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * ApiIdempotencyRecord 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface ApiIdempotencyRecordRepository extends JpaRepository<ApiIdempotencyRecord, Long> {


    /**
     * 복합 유일 키를 기준으로 신규 요청의 처리권을 원자적으로 확보합니다.
     * 호출부는 INSERT IGNORE의 갱신 행 수로 신규 삽입(1)과 기존 키(0)를 구분합니다.
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO api_idempotency_records (
                actor_user_id
              , operation
              , idempotency_key
              , request_hash
              , status
              , created_at
              , updated_at
            ) VALUES (
                :actorUserId
              , :operation
              , :idempotencyKey
              , :requestHash
              , 'PROCESSING'
              , :now
              , :now
            )
            """, nativeQuery = true)
    int insertProcessingIfAbsent(
            @Param("actorUserId") Long actorUserId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("now") Instant now
    );

    Optional<ApiIdempotencyRecord> findByActorUserIdAndOperationAndIdempotencyKey(
            Long actorUserId,
            String operation,
            String idempotencyKey
    );
}
