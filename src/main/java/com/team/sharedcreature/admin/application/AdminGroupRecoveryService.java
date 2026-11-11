package com.team.sharedcreature.admin.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature.group.api.MyGroupResponse;
import org.springframework.stereotype.Service;

/** 주 관리자 복구 명령과 독립 실패 감사를 조합합니다. */
@Service
public class AdminGroupRecoveryService {

    private final AdminGroupRecoveryCommandExecutor commandExecutor;
    private final AdminAuditService auditService;

    /** 복구 명령과 실패 감사 Service를 주입받습니다. */
    public AdminGroupRecoveryService(
            AdminGroupRecoveryCommandExecutor commandExecutor,
            AdminAuditService auditService
    ) {
        this.commandExecutor = commandExecutor;
        this.auditService = auditService;
    }

    /** 전체 관리자 주 관리자 복구를 실행합니다. */
    public MyGroupResponse transfer(Long actorId, Long groupId, Long targetId, String reason) {
        try {
            return commandExecutor.transfer(actorId, groupId, targetId, reason.trim());
        } catch (BusinessException exception) {
            auditService.recordFailure(
                    actorId, "TRANSFER_PRIMARY_AS_SUPER_ADMIN", "GROUP", groupId, exception.code()
            );
            throw exception;
        }
    }
}
