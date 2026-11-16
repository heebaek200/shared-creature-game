package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.admin.api.AdminReportResponse;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
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

    /** 신고와 사용자 표시 정보 저장소를 주입받습니다. */
    public AdminReportQueryService(
            UserReportRepository reportRepository,
            UserProfileRepository profileRepository
    ) {
        this.reportRepository = reportRepository;
        this.profileRepository = profileRepository;
    }

    /** 상태 필터와 고정 최신순으로 신고를 페이지 조회합니다. */
    public Page<AdminReportResponse> list(String status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<UserReport> reports = status == null
                ? reportRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : reportRepository.findByStatusOrderByCreatedAtDescIdDesc(status, pageable);
        return reports.map(this::toResponse);
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
                report.getHandledByUser() == null ? null : report.getHandledByUser().getId(),
                report.getResolution()
        );
    }
}
