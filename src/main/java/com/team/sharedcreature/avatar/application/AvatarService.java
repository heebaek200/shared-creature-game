package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature.avatar.api.AvatarResponse;
import com.team.sharedcreature.avatar.api.OwnedItemResponse;
import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.infrastructure.AvatarEquipmentRepository;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.payment.domain.Purchase;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;

/**
 * 사용자의 보유 아이템과 아바타 장착 정보를 조회합니다.
 */
@Service
@Transactional(readOnly = true)
public class AvatarService {

    private final OwnedItemRepository ownedItemRepository;
    private final AvatarEquipmentRepository avatarEquipmentRepository;
    private final UserRepository userRepository;
    private final PurchaseRepository purchaseRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final Clock clock;
    private final GroupMembershipRepository groupMembershipRepository;
    private final AvatarEquipmentEventPublisher avatarEquipmentEventPublisher;

    /**
     * 아바타 조회·변경과 변경 이벤트 예약에 필요한 의존성을 주입받습니다.
     *
     * @param ownedItemRepository 보유 아이템 Repository
     * @param avatarEquipmentRepository 아바타 장착 정보 Repository
     * @param userRepository 사용자 Repository
     * @param purchaseRepository 구매 Repository
     * @param refundRequestRepository 환불 요청 Repository
     * @param clock 서버 현재 시각의 기준
     * @param groupMembershipRepository 현재 소속 그룹 조회 Repository
     * @param avatarEquipmentEventPublisher Commit 이후 이벤트 발행기
     */
    public AvatarService(
            OwnedItemRepository ownedItemRepository,
            AvatarEquipmentRepository avatarEquipmentRepository,
            UserRepository userRepository,
            PurchaseRepository purchaseRepository,
            RefundRequestRepository refundRequestRepository,
            Clock clock,
            GroupMembershipRepository groupMembershipRepository,
            AvatarEquipmentEventPublisher avatarEquipmentEventPublisher
    ) {
        this.ownedItemRepository = ownedItemRepository;
        this.avatarEquipmentRepository = avatarEquipmentRepository;
        this.userRepository = userRepository;
        this.purchaseRepository = purchaseRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.clock = clock;
        this.groupMembershipRepository = groupMembershipRepository;
        this.avatarEquipmentEventPublisher = avatarEquipmentEventPublisher;
    }

    /**
     * 로그인한 사용자의 보유 아이템 목록을 조회합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param page 검증된 페이지 번호, 0 이상
     * @param size 검증된 페이지 크기, 1~50
     * @param slot 검증된 슬롯 코드, null이면 전체
     * @param status 검증된 보유 상태, null이면 전체
     * @return 현재 장착 여부를 포함한 보유 아이템 페이지
     */
    public Page<OwnedItemResponse> listOwnedItems(
            Long userId,
            int page,
            int size,
            String slot,
            String status
    ) {
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")
        );

        Page<OwnedItem> items = ownedItemRepository.findAllForUser(
                userId,
                slot,
                status,
                pageable
        );

        List<AvatarEquipment> equipments =
                avatarEquipmentRepository.findAllByUserId(userId);

        // 장착 여부를 아이템마다 DB에 묻지 않고 한 번 조회한 ID로 판단합니다.
        Set<Long> equippedItemIds = new HashSet<>();

        for (AvatarEquipment equipment : equipments) {
            OwnedItem equippedItem = equipment.getOwnedItem();

            if (equippedItem != null) {
                equippedItemIds.add(equippedItem.getId());
            }
        }

        return items.map(item -> OwnedItemResponse.from(
                item,
                equippedItemIds.contains(item.getId())
        ));
    }

    /**
     * 로그인한 사용자의 현재 아바타 장착 정보를 조회합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @return 미장착 슬롯을 null로 포함한 네 슬롯의 장착 정보
     */
    public AvatarResponse getAvatar(Long userId) {
        List<AvatarEquipment> equipments =
                avatarEquipmentRepository.findAllByUserId(userId);

        Map<String, AvatarResponse.Equipment> slots = new HashMap<>();

        for (AvatarEquipment equipment : equipments) {
            OwnedItem ownedItem = equipment.getOwnedItem();

            // 슬롯 행은 존재해도 장착 아이템이 없을 수 있습니다.
            if (ownedItem == null) {
                continue;
            }

            slots.put(
                    equipment.getSlotCode(),
                    AvatarResponse.Equipment.from(ownedItem)
            );
        }

        // Map에 슬롯 정보가 없으면 get()이 null을 반환합니다.
        return new AvatarResponse(
                userId,
                new AvatarResponse.Equipments(
                        slots.get("HEAD"),
                        slots.get("FACE"),
                        slots.get("BODY"),
                        slots.get("ACCESSORY")
                )
        );
    }

    /**
     * 본인의 보유 아이템을 지정한 아바타 슬롯에 장착합니다.
     *
     * 사용 확정과 슬롯 변경은 하나의 트랜잭션으로 처리합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slot 장착할 슬롯
     * @param ownedItemId 장착할 보유 아이템 ID
     * @param confirmFirstUse 최초 사용 동의 여부
     * @return 변경된 아바타의 전체 슬롯 정보
     * @throws BusinessException 잘못된 슬롯, 소유권·상태 위반,
     *                          환불 진행 또는 최초 사용 동의 누락인 경우
     */
    @Transactional
    public AvatarResponse equipAvatarItem(
            Long userId,
            String slot,
            Long ownedItemId,
            boolean confirmFirstUse
    ) {
        validateSlot(slot);

        // 같은 사용자의 장착·해제를 직렬화하고 최초 슬롯 생성 경합을 막습니다.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        Long purchaseId = ownedItemRepository
                .findPurchaseIdByIdAndUserId(ownedItemId, userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 구매 > 보유 아이템 > 환불 요청 순서로 잠금을 획득합니다.
        Purchase purchase = purchaseRepository.findByIdForUpdate(purchaseId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        if (!userId.equals(purchase.getUser().getId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        OwnedItem item = ownedItemRepository
                .findByIdAndUserIdForUpdate(ownedItemId, userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        // 최초 구매 ID 조회 이후에도 연결 관계가 같은지 재검사합니다.
        if (!purchaseId.equals(item.getPurchase().getId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        List<RefundRequest> refunds =
                refundRequestRepository.findAllByPurchaseIdForUpdate(purchaseId);

        if (item.getRevokedAt() != null || "REVOKED".equals(item.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        if (!"PAID".equals(purchase.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "결제 완료 상태의 아이템만 장착할 수 있습니다."
            );
        }

        if (!slot.equals(item.getShopItem().getSlotCode())) {
            throw new BusinessException(ErrorCode.SLOT_MISMATCH); // 아이템 슬롯과 장착 대상 슬롯이 다름
        }

        boolean refundInProgress = refunds.stream()
                .anyMatch(refund ->
                        Set.of("REQUESTED", "CANCEL_PENDING", "CANCEL_UNKNOWN")
                                .contains(refund.getStatus())
                );

        if (refundInProgress) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "환불 진행 중인 아이템은 장착할 수 없습니다."
            );
        }

        AvatarEquipment equipment = avatarEquipmentRepository
                .findByUserIdAndSlotCodeForUpdate(userId, slot)
                .orElse(null);

        // 같은 아이템을 다시 지정한 요청은 장착 변경 이벤트를 만들지 않습니다.
        boolean equipmentChanged = equipment == null
                || equipment.getOwnedItem() == null
                || !ownedItemId.equals(equipment.getOwnedItem().getId());

        // 잠금 대기가 끝난 뒤의 서버 시각으로 사용·변경 시각을 기록합니다.
        Instant now = clock.instant();

        if (equipment == null) {
            equipment = AvatarEquipment.createEmpty(user, slot, now);
        }

        item.confirmUse(confirmFirstUse, now);
        equipment.equip(item, now);

        avatarEquipmentRepository.save(equipment);

        // 응답 재조회 전에 사용 이력과 장착 변경을 DB에 반영합니다.
        // flush는 Commit이 아니므로 이후 실패하면 함께 롤백됩니다.
        avatarEquipmentRepository.flush();

        if (equipmentChanged) {
            scheduleEquipmentChangedEvent(
                    userId,
                    slot,
                    AvatarResponse.Equipment.from(item),
                    now
            );
        }

        return getAvatar(userId);
    }

    /**
     * 아바타 슬롯이 허용된 값인지 검증합니다.
     *
     * @param slot 검사할 슬롯 코드
     * @throws BusinessException 허용되지 않은 슬롯인 경우
     */
    private void validateSlot(String slot) {
        if (slot == null
                || !Set.of("HEAD", "FACE", "BODY", "ACCESSORY").contains(slot)) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE, // 허용하지 않는 슬롯
                    "슬롯은 HEAD, FACE, BODY, ACCESSORY 중 하나여야 합니다."
            );
        }
    }

    /**
     * 지정한 아바타 슬롯의 아이템을 해제합니다.
     *
     * 보유 아이템의 사용 상태와 최초 사용 시각은 변경하지 않습니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slot 해제할 슬롯
     * @return 변경된 아바타의 전체 슬롯 정보
     * @throws BusinessException 허용되지 않은 슬롯이거나 사용자가 없는 경우
     */
    @Transactional
    public AvatarResponse unequipAvatarItem(Long userId, String slot) {
        validateSlot(slot);

        // 장착과 같은 사용자 잠금을 먼저 획득해 동시 변경을 순서대로 처리합니다.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        AvatarEquipment equipment = avatarEquipmentRepository
                .findByUserIdAndSlotCodeForUpdate(userId, slot)
                .orElse(null);

        // 슬롯 행이 없거나 이미 비어 있다면 현재 아바타를 그대로 반환합니다.
        if (equipment == null || equipment.getOwnedItem() == null) {
            return getAvatar(userId);
        }

        Instant now = clock.instant();

        // 슬롯 연결만 제거하며 아이템의 OWNED_USED와 usedAt은 유지합니다.
        equipment.unequip(now);

        // 응답을 조회하기 전에 장착 해제 변경을 DB에 반영합니다.
        avatarEquipmentRepository.flush();

        // 해제 이벤트에는 변경 후 장착 아이템을 null로 전달합니다.
        scheduleEquipmentChangedEvent(userId, slot, null, now);

        return getAvatar(userId);
    }

    /**
     * 현재 소속 그룹에 전달할 아바타 변경 이벤트를 예약합니다.
     *
     * 호출자는 사용자 잠금을 확보한 쓰기 트랜잭션 안에서 호출해야 합니다.
     *
     * @param userId 아바타를 변경한 사용자 ID
     * @param slot 변경한 슬롯
     * @param equippedItem 변경 후 장착 아이템, 해제하면 null
     * @param occurredAt 변경 시각
     */
    private void scheduleEquipmentChangedEvent(
            Long userId,
            String slot,
            AvatarResponse.Equipment equippedItem,
            Instant occurredAt
    ) {
        // 그룹이 없어도 개인 아바타 변경은 허용하며 그룹 전파만 생략합니다.
        groupMembershipRepository.findActiveGroupIdByUserId(userId)
                .ifPresent(groupId ->
                        avatarEquipmentEventPublisher.publishAfterCommit(
                                new AvatarEquipmentChangedEvent(
                                        groupId,
                                        userId,
                                        slot,
                                        equippedItem,
                                        occurredAt
                                )
                        )
                );
    }
}