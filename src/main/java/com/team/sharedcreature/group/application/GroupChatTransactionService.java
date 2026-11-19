package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.UserProfile;
import com.team.sharedcreature.auth.infrastructure.UserProfileRepository;
import com.team.sharedcreature.group.api.GroupChatMessageResponse;
import com.team.sharedcreature.group.domain.ChatMessage;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.ChatMessageRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 채팅 권한 검증과 저장을 하나의 DB 트랜잭션으로 확정합니다. */
@Service
public class GroupChatTransactionService {

    private final GroupMembershipRepository membershipRepository;
    private final UserProfileRepository profileRepository;
    private final ChatMessageRepository messageRepository;
    private final Clock clock;

    /** 멤버십·프로필·메시지 저장소와 서버 시각을 주입받습니다. */
    public GroupChatTransactionService(
            GroupMembershipRepository membershipRepository,
            UserProfileRepository profileRepository,
            ChatMessageRepository messageRepository,
            Clock clock
    ) {
        this.membershipRepository = membershipRepository;
        this.profileRepository = profileRepository;
        this.messageRepository = messageRepository;
        this.clock = clock;
    }

    /** 활성 그룹원과 최신 닉네임을 확인한 뒤 메시지를 저장합니다. */
    @Transactional
    public GroupChatMessageResponse create(Long userId, String content) {
        GroupMembership membership = membershipRepository.findActiveWithGroupByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROLE_FORBIDDEN));
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        ChatMessage message = messageRepository.save(ChatMessage.create(
                membership.getGroup(), membership.getUser(), profile.getNickname(), content, now));
        return new GroupChatMessageResponse(
                message.getMessageId(),
                membership.getGroup().getId(),
                userId,
                message.getSenderNickname(),
                message.getContent(),
                message.getSentAt()
        );
    }
}
