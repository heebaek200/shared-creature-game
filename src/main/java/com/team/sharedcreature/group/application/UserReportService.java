package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.group.api.CreateUserReportRequest;
import com.team.sharedcreature.group.api.UserReportResponse;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 같은 그룹의 활성 회원 사이에서 사용자 신고를 접수합니다. */
@Service
public class UserReportService {

    private final GroupMembershipRepository membershipRepository;
    private final UserReportRepository reportRepository;
    private final Clock clock;

    /** 신고 접수에 필요한 멤버십과 신고 저장소를 주입받습니다. */
    public UserReportService(
            GroupMembershipRepository membershipRepository,
            UserReportRepository reportRepository,
            Clock clock
    ) {
        this.membershipRepository = membershipRepository;
        this.reportRepository = reportRepository;
        this.clock = clock;
    }

    /** 신고자와 대상의 현재 활성 소속을 확인하고 반복 가능한 신고를 저장합니다. */
    @Transactional
    public UserReportResponse create(Long reporterUserId, CreateUserReportRequest request) {
        if (reporterUserId.equals(request.reportedUserId())) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        GroupMembership reporter = membershipRepository.findActiveWithGroupByUserId(reporterUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));
        GroupMembership reported = membershipRepository.findActiveWithGroupByUserId(request.reportedUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!reporter.getGroup().getId().equals(reported.getGroup().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        UserReport report = reportRepository.save(UserReport.receive(
                reporter.getUser(),
                reported.getUser(),
                reporter.getGroup(),
                request.category(),
                request.content().trim(),
                clock.instant()
        ));
        return toResponse(report);
    }

    /** 신고 엔티티를 공통 응답으로 변환합니다. */
    public static UserReportResponse toResponse(UserReport report) {
        return new UserReportResponse(
                report.getId(),
                report.getGroup().getId(),
                report.getReporterUser().getId(),
                report.getReportedUser().getId(),
                report.getCategory(),
                report.getContent(),
                report.getStatus(),
                report.getCreatedAt(),
                report.getHandledAt()
        );
    }
}
