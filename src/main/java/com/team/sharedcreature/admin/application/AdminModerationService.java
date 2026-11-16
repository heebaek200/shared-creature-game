package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.admin.api.AdminReportResponse;
import org.springframework.stereotype.Service;

/** 신고 명령 실패 감사를 보장하고 최종 상세 응답을 반환합니다. */
@Service
public class AdminModerationService {

    private final AdminModerationCommandExecutor commandExecutor;
    private final AdminReportQueryService queryService;
    private final AdminAuditService auditService;

    /** 명령, 조회와 독립 실패 감사 Service를 조합합니다. */
    public AdminModerationService(
            AdminModerationCommandExecutor commandExecutor,
            AdminReportQueryService queryService,
            AdminAuditService auditService
    ) {
        this.commandExecutor = commandExecutor;
        this.queryService = queryService;
        this.auditService = auditService;
    }

    /** 신고 기각을 실행하고 처리 상세를 반환합니다. */
    public AdminReportResponse dismiss(Long actorId, Long reportId, String reason) {
        try {
            commandExecutor.dismiss(actorId, reportId, reason.trim());
            return queryService.get(reportId);
        } catch (BusinessException exception) {
            auditService.recordFailure(actorId, "DISMISS_REPORT", "USER_REPORT", reportId, exception.code());
            throw exception;
        }
    }

    /** 신고 기반 강퇴를 실행하고 처리 상세를 반환합니다. */
    public AdminReportResponse remove(Long actorId, Long reportId, String reason) {
        try {
            commandExecutor.remove(actorId, reportId, reason.trim());
            return queryService.get(reportId);
        } catch (BusinessException exception) {
            auditService.recordFailure(actorId, "REMOVE_REPORTED_MEMBER", "USER_REPORT", reportId, exception.code());
            throw exception;
        }
    }
}
