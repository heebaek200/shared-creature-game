package com.team.sharedcreature.group.infrastructure;

import com.team.sharedcreature.group.domain.UserReportChatEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 신고별 채팅 증거 저장과 상세 조회를 제공합니다. */
public interface UserReportChatEvidenceRepository
        extends JpaRepository<UserReportChatEvidence, Long> {

    Optional<UserReportChatEvidence> findByReportId(Long reportId);

}
