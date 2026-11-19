package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.api.AdminReportResponse;
import com.team.sharedcreature.admin.api.AdminReportListItemResponse;
import com.team.sharedcreature.admin.api.AdminReportChatEvidenceResponse;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.domain.UserReportStatus;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import com.team.sharedcreature.group.infrastructure.UserReportChatEvidenceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전체 관리자의 신고 목록·상세 조회를 구성합니다. */
@Service
@Transactional(readOnly = true)
public class AdminReportQueryService {

    private final UserReportRepository reportRepository;
    private final UserProfileRepository profileRepository;
    private final UserReportChatEvidenceRepository evidenceRepository;
    private final GroupMembershipRepository membershipRepository;

    /** 신고, 사용자 표시 정보와 현재 멤버십 저장소를 주입받습니다. */
    public AdminReportQueryService(
            UserReportRepository reportRepository,
            UserProfileRepository profileRepository,
            UserReportChatEvidenceRepository evidenceRepository,
            GroupMembershipRepository membershipRepository
    ) {
        this.reportRepository = reportRepository;
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.membershipRepository = membershipRepository;
    }

    /** 상태 필터와 고정 최신순으로 신고를 페이지 조회합니다. */
    public Page<AdminReportListItemResponse> list(UserReportStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<UserReport> reports = status == null
                ? reportRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : reportRepository.findByStatusOrderByCreatedAtDescIdDesc(status, pageable);
        return reports.map(this::toListItem);
    }

    /** 신고 상세와 처리 관리자·사유를 반환합니다. */
    public AdminReportResponse get(Long reportId) {
        return toResponse(reportRepository.findDetailById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    /** 신고 처리 응답을 구성합니다. */
    public AdminReportResponse toResponse(UserReport report) {
        String reporterNickname = profileRepository.findByUserId(report.getReporterUser().getId())
                .orElseThrow(() -> new IllegalStateException("신고자 프로필이 없습니다."))
                .getNickname();
        String reportedNickname = profileRepository.findByUserId(report.getReportedUser().getId())
                .orElseThrow(() -> new IllegalStateException("피신고자 프로필이 없습니다."))
                .getNickname();
        AdminReportChatEvidenceResponse evidence = evidenceRepository.findByReportId(report.getId())
                .map(value -> new AdminReportChatEvidenceResponse(
                        value.getChatMessageId(), value.getSenderUser().getId(),
                        value.getSenderNickname(), value.getContent(), value.getSentAt()))
                .orElse(null);
        return new AdminReportResponse(
                report.getId(),
                report.getGroup().getId(),
                report.getReporterUser().getId(),
                report.getReportedUser().getId(),
                report.getCategory(),
                report.getContent(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getHandledAt(),
                reportedNickname,
                reporterNickname,
                membershipRepository.findActiveMembershipId(
                        report.getGroup().getId(), report.getReportedUser().getId()).isPresent(),
                report.getHandledByUser() == null ? null : report.getHandledByUser().getId(),
                report.getResolution(),
                evidence
        );
    }

    private AdminReportListItemResponse toListItem(UserReport report) {
        return new AdminReportListItemResponse(
                report.getId(), report.getGroup().getId(), report.getReporterUser().getId(),
                report.getReportedUser().getId(), report.getCategory(), report.getContent(),
                report.getStatus(), report.getCreatedAt(), report.getHandledAt());
    }
}
