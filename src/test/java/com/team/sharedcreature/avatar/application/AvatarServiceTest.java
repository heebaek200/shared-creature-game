package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.avatar.domain.AvatarEquipment;
import com.team.sharedcreature.avatar.domain.OwnedItem;
import com.team.sharedcreature.avatar.infrastructure.AvatarEquipmentRepository;
import com.team.sharedcreature.avatar.infrastructure.OwnedItemRepository;
import com.team.sharedcreature.group.infrastructure.GroupMembershipRepository;
import com.team.sharedcreature.payment.domain.RefundRequest;
import com.team.sharedcreature.payment.infrastructure.PurchaseRepository;
import com.team.sharedcreature.payment.infrastructure.RefundRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 아바타 명령의 업무 제한과 잠금 호출 순서, 변경 이벤트 예약을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock private OwnedItemRepository ownedItemRepository;
    @Mock private AvatarEquipmentRepository equipmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private RefundRequestRepository refundRepository;
    @Mock private GroupMembershipRepository membershipRepository;
    @Mock private AvatarEquipmentEventPublisher eventPublisher;

    private AvatarService service;

    @BeforeEach
    void setUp() {
        service = new AvatarService(
                ownedItemRepository, equipmentRepository, userRepository,
                purchaseRepository, refundRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                membershipRepository, eventPublisher);
    }

    @Test
    void 최초_장착은_슬롯을_생성하고_사용_확정과_그룹_이벤트를_연결한다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        stubLockedItem(item, List.of());
        when(equipmentRepository.findByUserIdAndSlotCodeForUpdate(1L, "HEAD"))
                .thenReturn(Optional.empty());
        var savedSlot = new AtomicReference<AvatarEquipment>();
        when(equipmentRepository.save(any(AvatarEquipment.class))).thenAnswer(invocation -> {
            AvatarEquipment equipment = invocation.getArgument(0);
            savedSlot.set(equipment);
            return equipment;
        });
        when(equipmentRepository.findAllByUserId(1L))
                .thenAnswer(invocation -> List.of(savedSlot.get()));
        when(membershipRepository.findActiveGroupIdByUserId(1L)).thenReturn(Optional.of(7L));

        var response = service.equipAvatarItem(1L, "HEAD", 10L, true);

        assertThat(item.getStatus()).isEqualTo("OWNED_USED");
        assertThat(item.getUsedAt()).isEqualTo(NOW);
        assertThat(response.equipments().HEAD().ownedItemId()).isEqualTo(10L);
        assertThat(response.equipments().FACE()).isNull();
        var event = ArgumentCaptor.forClass(AvatarEquipmentChangedEvent.class);
        verify(eventPublisher).publishAfterCommit(event.capture());
        assertThat(event.getValue().groupId()).isEqualTo(7L);
        assertThat(event.getValue().userId()).isEqualTo(1L);
        assertThat(event.getValue().slot()).isEqualTo("HEAD");
        assertThat(event.getValue().equippedItem().ownedItemId()).isEqualTo(10L);
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);

        // 실제 DB의 경합 재현과 구분해 업무 코드의 잠금 획득 순서를 검증합니다.
        var order = inOrder(userRepository, purchaseRepository, ownedItemRepository,
                refundRepository, equipmentRepository, eventPublisher);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(purchaseRepository).findByIdForUpdate(item.getPurchase().getId());
        order.verify(ownedItemRepository).findByIdAndUserIdForUpdate(10L, 1L);
        order.verify(refundRepository).findAllByPurchaseIdForUpdate(item.getPurchase().getId());
        order.verify(equipmentRepository).findByUserIdAndSlotCodeForUpdate(1L, "HEAD");
        order.verify(equipmentRepository).flush();
        order.verify(eventPublisher).publishAfterCommit(any());
    }

    @Test
    void 최초_사용_미동의는_저장과_이벤트_예약_전에_거부한다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        stubLockedItem(item, List.of());
        when(equipmentRepository.findByUserIdAndSlotCodeForUpdate(1L, "HEAD"))
                .thenReturn(Optional.empty());

        assertBusinessError(() -> service.equipAvatarItem(1L, "HEAD", 10L, false),
                ErrorCode.FIRST_USE_CONFIRMATION_REQUIRED);

        assertThat(item.getUsedAt()).isNull();
        verify(equipmentRepository, never()).save(any());
        verifyNoInteractions(membershipRepository, eventPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "CANCEL_PENDING", "CANCEL_UNKNOWN"})
    void 환불_진행_중에는_사용_확정과_장착을_거부한다(String status) {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        RefundRequest refund = mock(RefundRequest.class);
        when(refund.getStatus()).thenReturn(status);
        stubLockedItem(item, List.of(refund));

        assertBusinessError(() -> service.equipAvatarItem(1L, "HEAD", 10L, true),
                ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(item.getStatus()).isEqualTo("OWNED_UNUSED");
        assertThat(item.getUsedAt()).isNull();
        verifyNoInteractions(equipmentRepository, eventPublisher);
    }

    @Test
    void 본인_아이템이_아니면_구매_존재_여부를_추가로_조회하지_않는다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L)));
        when(ownedItemRepository.findPurchaseIdByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());

        assertBusinessError(() -> service.equipAvatarItem(1L, "HEAD", 10L, true),
                ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(purchaseRepository, refundRepository, equipmentRepository, eventPublisher);
    }

    @Test
    void 회수된_아이템은_장착하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        ReflectionTestUtils.setField(item, "status", "REVOKED");
        ReflectionTestUtils.setField(item, "revokedAt", NOW);
        stubLockedItem(item, List.of());

        assertBusinessError(() -> service.equipAvatarItem(1L, "HEAD", 10L, true),
                ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(equipmentRepository, eventPublisher);
    }

    @Test
    void 슬롯이_다르면_최초_사용을_확정하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "FACE");
        stubLockedItem(item, List.of());

        assertBusinessError(() -> service.equipAvatarItem(1L, "HEAD", 10L, true),
                ErrorCode.SLOT_MISMATCH);

        assertThat(item.getUsedAt()).isNull();
        verifyNoInteractions(equipmentRepository, eventPublisher);
    }

    @Test
    void 같은_아이템_재장착은_사용_시각을_보존하고_이벤트를_중복_예약하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        item.confirmUse(true, CREATED_AT);
        AvatarEquipment equipment = equippedSlot(item);
        stubLockedItem(item, List.of());
        stubExistingSlot(equipment);

        var response = service.equipAvatarItem(1L, "HEAD", 10L, false);

        assertThat(response.equipments().HEAD().ownedItemId()).isEqualTo(10L);
        assertThat(item.getUsedAt()).isEqualTo(CREATED_AT);
        assertThat(equipment.getUpdatedAt()).isEqualTo(CREATED_AT);
        verifyNoInteractions(membershipRepository, eventPublisher);
    }

    @Test
    void 그룹이_없어도_개인_아바타_장착은_가능하다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);
        stubLockedItem(item, List.of());
        stubExistingSlot(equipment);
        when(membershipRepository.findActiveGroupIdByUserId(1L)).thenReturn(Optional.empty());

        var response = service.equipAvatarItem(1L, "HEAD", 10L, true);

        assertThat(response.equipments().HEAD().ownedItemId()).isEqualTo(10L);
        assertThat(item.getStatus()).isEqualTo("OWNED_USED");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 해제는_사용_이력을_보존하고_null_아이템_이벤트를_예약한다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        item.confirmUse(true, CREATED_AT);
        AvatarEquipment equipment = equippedSlot(item);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(item.getUser()));
        stubExistingSlot(equipment);
        when(membershipRepository.findActiveGroupIdByUserId(1L)).thenReturn(Optional.of(7L));

        var response = service.unequipAvatarItem(1L, "HEAD");

        assertThat(response.equipments().HEAD()).isNull();
        assertThat(item.getStatus()).isEqualTo("OWNED_USED");
        assertThat(item.getUsedAt()).isEqualTo(CREATED_AT);
        var event = ArgumentCaptor.forClass(AvatarEquipmentChangedEvent.class);
        verify(eventPublisher).publishAfterCommit(event.capture());
        assertThat(event.getValue().equippedItem()).isNull();
        assertThat(event.getValue().slot()).isEqualTo("HEAD");
        assertThat(event.getValue().groupId()).isEqualTo(7L);
        verifyNoInteractions(purchaseRepository, ownedItemRepository, refundRepository);
    }

    @Test
    void 없는_슬롯의_해제는_빈_아바타를_반환하고_이벤트를_예약하지_않는다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L)));
        when(equipmentRepository.findByUserIdAndSlotCodeForUpdate(1L, "HEAD"))
                .thenReturn(Optional.empty());
        when(equipmentRepository.findAllByUserId(1L)).thenReturn(List.of());

        var response = service.unequipAvatarItem(1L, "HEAD");

        assertThat(response.equipments().HEAD()).isNull();
        assertThat(response.equipments().FACE()).isNull();
        assertThat(response.equipments().BODY()).isNull();
        assertThat(response.equipments().ACCESSORY()).isNull();
        verify(equipmentRepository, never()).flush();
        verifyNoInteractions(eventPublisher, membershipRepository);
    }

    /** 구매·보유 아이템·환불 상태를 잠금 조회하는 경로만 준비합니다. */
    private void stubLockedItem(OwnedItem item, List<RefundRequest> refunds) {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(item.getUser()));
        when(ownedItemRepository.findPurchaseIdByIdAndUserId(item.getId(), 1L))
                .thenReturn(Optional.of(item.getPurchase().getId()));
        when(purchaseRepository.findByIdForUpdate(item.getPurchase().getId()))
                .thenReturn(Optional.of(item.getPurchase()));
        when(ownedItemRepository.findByIdAndUserIdForUpdate(item.getId(), 1L))
                .thenReturn(Optional.of(item));
        when(refundRepository.findAllByPurchaseIdForUpdate(item.getPurchase().getId()))
                .thenReturn(refunds);
    }

    /** 기존 슬롯의 잠금 조회와 변경 후 응답 조회를 준비합니다. */
    private void stubExistingSlot(AvatarEquipment equipment) {
        when(equipmentRepository.findByUserIdAndSlotCodeForUpdate(1L, "HEAD"))
                .thenReturn(Optional.of(equipment));
        when(equipmentRepository.findAllByUserId(1L)).thenReturn(List.of(equipment));
    }

    /** 사용 확정된 아이템이 장착된 슬롯을 준비합니다. */
    private AvatarEquipment equippedSlot(OwnedItem item) {
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);
        equipment.equip(item, CREATED_AT);
        return equipment;
    }

    /** HTTP 계층과 구분해 업무 예외의 안정적인 오류 코드를 검사합니다. */
    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
