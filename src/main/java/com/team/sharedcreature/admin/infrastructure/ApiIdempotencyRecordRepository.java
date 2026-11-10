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
