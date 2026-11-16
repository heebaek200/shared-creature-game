package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.domain.AdminAuditLog;
import com.team.sharedcreature.admin.infrastructure.AdminAuditLogRepository;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 롤백되는 관리자 명령과 분리하여 업무 실패 감사 이력을 보존합니다. */
@Service
public class AdminAuditService {

    private final AdminAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 실패 감사 기록에 필요한 저장소와 시계를 주입받습니다. */
    public AdminAuditService(
            AdminAuditLogRepository auditLogRepository,
            UserRepository userRepository,
            Clock clock
    ) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** 권한 확인 뒤 발생한 업무 실패를 독립 트랜잭션으로 기록합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long actorId, String actionType, String targetType, Long targetId, String code) {
        var actor = userRepository.findById(actorId).orElse(null);
        if (actor == null || !"SUPER_ADMIN".equals(actor.getGlobalRole())) {
            return;
        }
        auditLogRepository.save(AdminAuditLog.create(
                actor,
                actionType,
                targetType,
                targetId,
                null,
                "FAILED",
                code,
                "{}",
                clock.instant()
        ));
    }
}
