package com.team.sharedcreature.avatar.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 장착 요청의 선택 필드와 미장착 슬롯의 JSON 계약을 검증합니다. */
class AvatarJsonContractTest {

    // 기존 애플리케이션의 미등록 필드 거부 설정만 재현하고 전역 설정은 추가하지 않습니다.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void 최초_사용_동의_생략은_false로_처리한다() {
        var request = mapper.readValue("{\"ownedItemId\":10}", EquipAvatarItemRequest.class);

        assertThat(request.ownedItemId()).isEqualTo(10L);
        assertThat(request.confirmFirstUse()).isFalse();
    }

    @Test
    void 명시적인_최초_사용_동의를_전달한다() {
        var request = mapper.readValue(
                "{\"ownedItemId\":10,\"confirmFirstUse\":true}", EquipAvatarItemRequest.class);

        assertThat(request.confirmFirstUse()).isTrue();
    }

    @Test
    void 명시적인_null은_생략과_구분해_거부한다() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"ownedItemId\":10,\"confirmFirstUse\":null}", EquipAvatarItemRequest.class))
                .isInstanceOf(InvalidNullException.class);
    }

    @Test
    void 요청에_없는_사용자_ID_필드를_추가할_수_없다() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"ownedItemId\":10,\"userId\":2}", EquipAvatarItemRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void 미장착_네_슬롯을_대문자_키와_null로_모두_반환한다() {
        var response = new AvatarResponse(1L, new AvatarResponse.Equipments(null, null, null, null));

        var equipments = mapper.readTree(mapper.writeValueAsString(response)).get("equipments");

        assertThat(equipments.size()).isEqualTo(4);
        for (String slot : new String[]{"HEAD", "FACE", "BODY", "ACCESSORY"}) {
            assertThat(equipments.has(slot)).isTrue();
            assertThat(equipments.get(slot).isNull()).isTrue();
        }
    }
}
