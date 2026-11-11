package com.team.sharedcreature.avatar.domain;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 최초 사용 확정과 슬롯 변경에 따른 사용 이력 보존 규칙을 검증합니다. */
class AvatarEquipmentDomainTest {

    @Test
    void 최초_사용_동의가_없으면_상태와_시각을_변경하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");

        assertThatThrownBy(() -> item.confirmUse(false, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.FIRST_USE_CONFIRMATION_REQUIRED));

        assertThat(item.getStatus()).isEqualTo("OWNED_UNUSED");
        assertThat(item.getUsedAt()).isNull();
        assertThat(item.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 해제와_재장착_이후에도_최초_사용_시각과_사용_상태를_보존한다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);
        item.confirmUse(true, NOW);
        equipment.equip(item, NOW);

        equipment.unequip(NOW.plusSeconds(1));
        assertThat(equipment.getOwnedItem()).isNull();
        assertThat(item.getStatus()).isEqualTo("OWNED_USED");
        assertThat(item.getUsedAt()).isEqualTo(NOW);

        // 재장착에는 다시 동의하지 않아도 되며 첫 사용 시각을 덮어쓰면 안 됩니다.
        item.confirmUse(false, NOW.plusSeconds(2));
        equipment.equip(item, NOW.plusSeconds(2));
        assertThat(equipment.getOwnedItem()).isSameAs(item);
        assertThat(item.getUsedAt()).isEqualTo(NOW);
        assertThat(item.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void 아이템을_교체해도_이전_아이템의_사용_이력은_남는다() {
        OwnedItem previous = unusedItem(10L, 1L, "HEAD");
        OwnedItem replacement = unusedItem(11L, 1L, "HEAD");
        AvatarEquipment equipment = AvatarEquipment.createEmpty(previous.getUser(), "HEAD", CREATED_AT);
        previous.confirmUse(true, NOW);
        equipment.equip(previous, NOW);
        replacement.confirmUse(true, NOW.plusSeconds(1));

        equipment.equip(replacement, NOW.plusSeconds(1));

        assertThat(equipment.getOwnedItem()).isSameAs(replacement);
        assertThat(previous.getStatus()).isEqualTo("OWNED_USED");
        assertThat(previous.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    void 다른_사용자의_아이템은_슬롯에_연결하지_않는다() {
        OwnedItem item = unusedItem(10L, 2L, "HEAD");
        item.confirmUse(true, NOW);
        AvatarEquipment equipment = AvatarEquipment.createEmpty(user(1L), "HEAD", CREATED_AT);

        assertThatThrownBy(() -> equipment.equip(item, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(equipment.getOwnedItem()).isNull();
    }

    @Test
    void 다른_슬롯의_아이템은_장착하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "FACE");
        item.confirmUse(true, NOW);
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);

        assertThatThrownBy(() -> equipment.equip(item, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SLOT_MISMATCH));
        assertThat(equipment.getOwnedItem()).isNull();
    }

    @Test
    void 사용_확정_전에는_슬롯에_장착하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);

        assertThatThrownBy(() -> equipment.equip(item, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void 회수된_아이템은_사용_확정과_장착을_모두_거부한다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        ReflectionTestUtils.setField(item, "status", "REVOKED");
        ReflectionTestUtils.setField(item, "revokedAt", NOW);
        AvatarEquipment equipment = AvatarEquipment.createEmpty(item.getUser(), "HEAD", CREATED_AT);

        assertThatThrownBy(() -> item.confirmUse(true, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> equipment.equip(item, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(item.getUsedAt()).isNull();
    }

    @Test
    void 사용_상태인데_사용_시각이_없으면_임의로_복구하지_않는다() {
        OwnedItem item = unusedItem(10L, 1L, "HEAD");
        ReflectionTestUtils.setField(item, "status", "OWNED_USED");

        assertThatThrownBy(() -> item.confirmUse(true, NOW))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        assertThat(item.getUsedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "FACE", "BODY", "ACCESSORY"})
    void 허용된_네_슬롯을_생성하고_빈_슬롯의_해제를_허용한다(String slot) {
        AvatarEquipment equipment = AvatarEquipment.createEmpty(user(1L), slot, CREATED_AT);

        equipment.unequip(NOW);

        assertThat(equipment.getSlotCode()).isEqualTo(slot);
        assertThat(equipment.getOwnedItem()).isNull();
        assertThat(equipment.getUpdatedAt()).isEqualTo(CREATED_AT);
    }
}
