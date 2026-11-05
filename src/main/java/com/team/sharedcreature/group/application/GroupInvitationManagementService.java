
package com.team.sharedcreature.group.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.Hashing;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.api.InvitationManagementResponse;
import com.team.sharedcreature.group.api.RotateInvitationResponse;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.domain.GroupInviteLink;
import com.team.sharedcreature.group.domain.GroupMembership;
import com.team.sharedcreature.group.infrastructure.GroupInviteLinkRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.group.infrastructure.GroupRepository;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 현재 그룹의 초대 링크 조회 및 재발급을 처리합니다.
 *
 * PRIMARY 권한을 최신 DB 멤버십으로 검사합니다.
 * 재발급은 사용자 → 그룹 → 초대 링크 → 멤버십 잠금 순서를 사용합니다.
 * 기존 링크 폐기와 신규 링크 발급을 하나의 트랜잭션에서 확정합니다.
 */
@Service
public class GroupInvitationManagementService {

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final GroupInviteLinkRepository inviteLinkRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final String appBaseUrl;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 초대 링크 관리에 필요한 의존성을 주입받습니다.
     *
     * EntityManager는 기존 초대 링크의 상태 변경을 먼저 Flush하는 데 사용합니다.
     * Clock은 테스트에서 발급 시각을 고정할 수 있도록 주입합니다.
     * 앱 기본 URL은 그룹 생성 API와 동일한 설정값을 사용합니다.
     */
    public GroupInvitationManagementService(
            UserRepository userRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            GroupInviteLinkRepository inviteLinkRepository,
            EntityManager entityManager,
            Clock clock,
            @Value("${APP_BASE_URL:http://localhost:5173}") String appBaseUrl
    ) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.inviteLinkRepository = inviteLinkRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * 현재 그룹의 활성 초대 링크 관리 정보를 반환합니다.
     *
     * 조회 시에도 활성 멤버십과 PRIMARY 역할을 재검증합니다.
     * 저장된 토큰은 해시이므로 초대 URL을 반환하지 않습니다.
     * 활성 링크가 없는 비정상 상태에서는 임의로 링크를 만들지 않습니다.
     *
     * @param actorId 요청 사용자 ID
     * @return 초대 링크 발급 정보
     */
    @Transactional(readOnly = true)
    public InvitationManagementResponse getInvitationManagement(Long actorId) {
        // 현재 사용자의 활성 멤버십을 그룹과 함께 조회합니다.
        GroupMembership membership = membershipRepository
                .findActiveWithGroupByUserId(actorId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        Group group = membership.getGroup();

        // 최신 DB 정보를 기준으로 관리자 권한을 확인합니다.
        validatePrimary(group, membership, actorId);

        // 활성 링크의 발급 이력만 조회하며 토큰 해시는 노출하지 않습니다.
        GroupInviteLink invitation = inviteLinkRepository
                .findByGroup_IdAndStatus(group.getId(), "ACTIVE")
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
                );

        return new InvitationManagementResponse(
                group.getId(),
                invitation.getIssuedAt(),
                invitation.getIssuedByUser().getId(),
                true,
                null
        );
    }

    /**
     * 현재 그룹의 초대 링크를 회전하여 새 URL을 발급합니다.
     *
     * 요청 사용자와 그룹, 기존 초대 링크, 멤버십을 순서대로 잠급니다.
     * PRIMARY 권한을 잠금 이후 다시 검증합니다.
     * 기존 링크 UPDATE를 먼저 Flush한 다음 새 링크를 INSERT합니다.
     * 결과 URL은 응답에서만 한 번 제공하며 DB에는 SHA-256 해시만 남깁니다.
     *
     * @param actorId 초대 재발급을 실행한 사용자 ID
     * @return 새 초대 URL과 발급 정보
     */
    @Transactional
    public RotateInvitationResponse rotateInvitation(Long actorId) {
        // 1. 잠금 전에는 활성 그룹 ID만 사전 조회합니다.
        Long groupId = membershipRepository
                .findActiveGroupIdByUserId(actorId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        // 2. 사용자 행에 먼저 쓰기 잠금을 획득합니다.
        User actor = userRepository.findByIdForUpdate(actorId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        // 3. 그룹 행을 잠급니다.
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        if (!"ACTIVE".equals(group.getStatus())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        // 4. 기존 활성 초대 링크를 쓰기 잠금으로 조회합니다.
        GroupInviteLink previousInvitation = inviteLinkRepository
                .findActiveByGroupIdForUpdate(groupId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
                );

        // 5. 관리자 멤버십도 잠금 이후의 최신 상태로 조회합니다.
        Long membershipId = membershipRepository
                .findActiveMembershipId(groupId, actorId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ROLE_FORBIDDEN)
                );

        GroupMembership membership = membershipRepository
                .findByIdForUpdate(membershipId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.CONCURRENT_MODIFICATION)
                );

        // 6. 사용자와 멤버십의 식별자 및 역할을 재검증합니다.
        if (!actorId.equals(actor.getId())
                || !"ACTIVE".equals(actor.getStatus())
                || !actorId.equals(membership.getUser().getId())
                || !groupId.equals(membership.getGroup().getId())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }

        validatePrimary(group, membership, actorId);
        Instant now = Instant.now(clock);

        // 7. 기존 활성 링크를 INVALIDATED로 변경합니다.
        previousInvitation.invalidate(now);

        // 8. 기존 링크의 UPDATE를 먼저 DB에 반영합니다.
        //    그룹별 활성 링크 UNIQUE 제약 때문에 순서가 중요합니다.
        entityManager.flush();

        // 9. 새 원문 토큰을 만들고 해시만 저장합니다.
        String rawToken = createToken();
        String tokenHash = Hashing.sha256(rawToken);

        inviteLinkRepository.save(
                GroupInviteLink.issue(group, tokenHash, actor, now)
        );

        // 10. 원문 토큰을 포함하는 URL은 응답에만 담습니다.
        return new RotateInvitationResponse(
                groupId,
                appBaseUrl + "/invite/" + rawToken,
                now,
                actorId,
                true,
                null
        );
    }

    /**
     * 그룹의 PRIMARY 권한을 실제 DB 상태로 검증합니다.
     *
     * 그룹은 ACTIVE여야 하며 해당 멤버십도 ACTIVE여야 합니다.
     * 역할 문자열뿐 아니라 그룹의 주 관리자 FK도 일치해야 합니다.
     * 탈퇴하거나 권한이 이전된 과거 관리자는 접근할 수 없습니다.
     *
     * @param group 검증할 그룹
     * @param membership 요청자 멤버십
     * @param actorId 요청자 사용자 ID
     */
    private void validatePrimary(
            Group group,
            GroupMembership membership,
            Long actorId
    ) {
        // 그룹·멤버십·사용자 상태를 검사합니다.
        if (!"ACTIVE".equals(group.getStatus())
                || !"ACTIVE".equals(membership.getStatus())
                || !"ACTIVE".equals(membership.getUser().getStatus())
                || !actorId.equals(membership.getUser().getId())
                || !group.getId().equals(membership.getGroup().getId())
                || !"PRIMARY".equals(membership.getRole())
                || group.getPrimaryAdminUser() == null
                || !actorId.equals(group.getPrimaryAdminUser().getId())) {
            throw new BusinessException(ErrorCode.ROLE_FORBIDDEN);
        }
    }

    /**
     * 예측하기 어려운 새 초대 토큰을 생성합니다.
     *
     * SecureRandom으로 32바이트의 난수를 생성합니다.
     * URL-safe Base64 인코딩을 적용하고 패딩을 제거합니다.
     * 기존 초대 처리 API의 토큰 형식과 동일하게 유지합니다.
     *
     * @return URL-safe 원문 초대 토큰
     */
    private String createToken() {
        // 256비트 난수에서 URL-safe 토큰을 생성합니다.
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }
}
