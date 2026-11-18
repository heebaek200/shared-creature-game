package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.domain.AvatarSlot;
import com.team.sharedcreature.avatar.domain.ShopItemStatus;
import com.team.sharedcreature.avatar.infrastructure.ShopItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static com.team.sharedcreature.avatar.support.AvatarTestFixtures.unusedItem;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Enum 전환 이후에도 상품 노출 조건과 응답 문자열 계약이 유지되는지 검증합니다. */
@ExtendWith(MockitoExtension.class)
class ShopQueryServiceTest {
    @Mock private ShopItemRepository repository;
    private ShopQueryService service;

    @BeforeEach
    void 준비() {
        service = new ShopQueryService(repository);
    }

    @ParameterizedTest
    @EnumSource(AvatarSlot.class)
    void 슬롯은_Enum으로_조회하고_응답은_문자열로_반환한다(AvatarSlot slot) {
        var shop = unusedItem(10L, 1L, slot.name()).getShopItem();
        when(repository.findAllByStatusAndSlotCode(eq(ShopItemStatus.ACTIVE), eq(slot), any()))
                .thenReturn(new PageImpl<>(List.of(shop)));

        var result = service.list(0, 20, slot.name()).getContent().getFirst();

        assertThat(result.slot()).isEqualTo(slot);
        assertThat(result.status()).isEqualTo(ShopItemStatus.ACTIVE);
    }

    @Test
    void 슬롯_생략은_판매중인_전체슬롯을_조회한다() {
        when(repository.findAllByStatus(eq(ShopItemStatus.ACTIVE), any())).thenReturn(Page.empty());

        assertThat(service.list(0, 20, null)).isEmpty();
        verify(repository, never()).findAllByStatusAndSlotCode(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"head", " HEAD", "HEAD ", "", "HAT"})
    void 허용값과_정확히_일치하지_않는_슬롯은_거절한다(String slot) {
        assertThatThrownBy(() -> service.list(0, 20, slot))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
        verifyNoInteractions(repository);
    }

    @Test
    void 상품상세도_판매중인_조건으로_조회한다() {
        var shop = unusedItem(10L, 1L, "HEAD").getShopItem();
        when(repository.findByIdAndStatus(110L, ShopItemStatus.ACTIVE)).thenReturn(Optional.of(shop));

        assertThat(service.get(110L).status()).isEqualTo(ShopItemStatus.ACTIVE);
    }

    @Test
    void 없거나_판매중이_아닌_상품은_기존_404오류로_거절한다() {
        when(repository.findByIdAndStatus(110L, ShopItemStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(110L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
