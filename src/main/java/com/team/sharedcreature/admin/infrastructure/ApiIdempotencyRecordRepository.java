package com.team.sharedcreature.admin.infrastructure;

import com.team.sharedcreature.admin.domain.ApiIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ApiIdempotencyRecord 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface ApiIdempotencyRecordRepository extends JpaRepository<ApiIdempotencyRecord, Long> {
}
