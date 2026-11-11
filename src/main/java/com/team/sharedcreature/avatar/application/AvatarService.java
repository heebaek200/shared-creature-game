package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature.avatar.api.AvatarResponse;
import com.team.sharedcreature.avatar.api.OwnedItemResponse;
import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.infrastructure.AvatarEquipmentRepository;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
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

/**
 * 사용자의 보유 아이템과 아바타 장착 정보를 조회합니다.
 */
@Service
@Transactional(readOnly = true)
public class AvatarService {

    private final OwnedItemRepository ownedItemRepository;
    private final AvatarEquipmentRepository avatarEquipmentRepository;

    /**
     * 보유 아이템과 아바타 장착 정보 Repository를 주입받습니다.
     *
     * @param ownedItemRepository 보유 아이템 Repository
     * @param avatarEquipmentRepository 아바타 장착 정보 Repository
     */
    public AvatarService(
            OwnedItemRepository ownedItemRepository,
            AvatarEquipmentRepository avatarEquipmentRepository
    ) {
        this.ownedItemRepository = ownedItemRepository;
        this.avatarEquipmentRepository = avatarEquipmentRepository;
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
}