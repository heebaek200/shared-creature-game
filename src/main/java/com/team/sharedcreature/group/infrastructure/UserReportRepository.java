package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.UserReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * UserReport 엔티티의 기본 영속성 연산을 제공합니다.
 */
public interface UserReportRepository extends JpaRepository<UserReport, Long> {
    Page<UserReport> findByStatusOrderByCreatedAtDescIdDesc(String status, Pageable pageable);

    Page<UserReport> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("SELECT report FROM UserReport report WHERE report.id = :reportId")
    Optional<UserReport> findDetailById(@Param("reportId") Long reportId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT report FROM UserReport report WHERE report.id = :reportId")
    Optional<UserReport> findByIdForUpdate(@Param("reportId") Long reportId);

    long countByStatus(String status);
}
