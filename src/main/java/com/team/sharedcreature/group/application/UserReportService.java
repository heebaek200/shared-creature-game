package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.group.api.UserReportResponse;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.domain.UserReport;
import com.team.sharedcreature.group.domain.UserReportCategory;
import com.team.sharedcreature.group.domain.ChatMessage;
import com.team.sharedcreature.group.domain.UserReportChatEvidence;
import com.team.sharedcreature.group.infrastructure.ChatMessageRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.UserReportChatEvidenceRepository;
import com.team.sharedcreature.group.infrastructure.UserReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 같은 그룹의 활성 회원 사이에서 사용자 신고를 접수합니다. */
@Service
public class UserReportService {

    private final GroupMembershipRepository membershipRepository;
    private final UserReportRepository reportRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserReportChatEvidenceRepository evidenceRepository;
    private final Clock clock;

    /** 신고 접수에 필요한 멤버십과 신고 저장소를 주입받습니다. */
    public UserReportService(
            GroupMembershipRepository membershipRepository,
            UserReportRepository reportRepository,
            ChatMessageRepository chatMessageRepository,
            UserReportChatEvidenceRepository evidenceRepository,
            Clock clock
    ) {
        this.membershipRepository = membershipRepository;
        this.reportRepository = reportRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.evidenceRepository = evidenceRepository;
        this.clock = clock;
    }

    /** 신고자와 대상의 현재 활성 소속을 확인하고 반복 가능한 신고를 저장합니다. */
    @Transactional
    public UserReportResponse create(
            Long reporterUserId,
            Long reportedUserId,
            UserReportCategory category,
            String content,
            String chatMessageId
    ) {
        if (reporterUserId.equals(reportedUserId)) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        GroupMembership reporter = membershipRepository.findActiveWithGroupByUserId(reporterUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));
        if (category == null) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE);
        }
        var now = clock.instant();
        UserReport report;
        if (chatMessageId == null) {
            GroupMembership reported = membershipRepository.findActiveWithGroupByUserId(reportedUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (!reporter.getGroup().getId().equals(reported.getGroup().getId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            report = reportRepository.save(UserReport.receive(
                    reporter.getUser(), reported.getUser(), reporter.getGroup(),
                    category, content.trim(), now));
        } else {
            ChatMessage message = chatMessageRepository.findByMessageIdForUpdate(chatMessageId)
                    .filter(value -> value.getExpiresAt().isAfter(now))
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (!reporter.getGroup().getId().equals(message.getGroup().getId())
                    || !reportedUserId.equals(message.getSenderUser().getId())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            report = reportRepository.save(UserReport.receive(
                    reporter.getUser(), message.getSenderUser(), message.getGroup(),
                    category, content.trim(), now));
            evidenceRepository.save(UserReportChatEvidence.preserve(report, message, now));
        }
        return toResponse(report);
    }

    /** 기존 일반 신고 호출부를 유지합니다. */
    @Transactional
    public UserReportResponse create(
            Long reporterUserId,
            Long reportedUserId,
            UserReportCategory category,
            String content
    ) {
        return create(reporterUserId, reportedUserId, category, content, null);
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
