package com.team.sharedcreature.avatar.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature.avatar.application.ShopQueryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 상점 상품 목록과 상세 조회 요청을 처리합니다.
 *
 * 입력값을 검증하고 조회 업무는 Service에 위임합니다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/shop-items")
public class ShopController {

    private final ShopQueryService shopQueryService;

    /**
     * 상점 상품 조회 Service를 주입받습니다.
     *
     * @param shopQueryService 상점 상품 조회 Service
     */
    public ShopController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    /**
     * 판매 중인 상품 목록을 조회합니다.
     *
     * @param page 페이지 번호, 생략하면 0
     * @param size 페이지 크기, 생략하면 20
     * @param slot 장착 슬롯, 생략하면 전체
     * @return 상품 목록과 페이지 메타데이터
     * @throws BusinessException 페이지 번호·크기가 올바르지 않은 경우
     */
    @GetMapping // 	0페이지, 20개 기준 조회
    public ApiResponse<List<ShopItemResponse>> listShopItems(
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "slot", required = false)
            @Pattern(
                    regexp = "HEAD|FACE|BODY|ACCESSORY",
                    message = "슬롯은 HEAD, FACE, BODY, ACCESSORY 중 하나여야 합니다."
            )
            String slot
    ) {
        // 생략한 값에만 기본값을 적용하고 빈 문자열은 잘못된 입력으로 거부합니다.
        int pageNumber = parseInteger(page, 0, 0, Integer.MAX_VALUE, "page");
        int pageSize = parseInteger(size, 20, 1, 50, "size");

        Page<ShopItemResponse> result =
                shopQueryService.list(pageNumber, pageSize, slot);

        // 기존 공통 응답을 사용해 data 배열과 meta 페이지 정보를 구성합니다.
        return ApiResponse.page(result);
    }

    /**
     * 판매 중인 상품의 상세 정보를 조회합니다.
     *
     * @param itemId 상품 ID, 1 이상
     * @return 상품 상세 정보를 담은 공통 응답
     */
    @GetMapping("/{itemId}") // 판매 중인 상품id 조회
    public ApiResponse<ShopItemResponse> getShopItem(
            @PathVariable(name = "itemId")
            @Min(value = 1, message = "상품 ID는 1 이상이어야 합니다.")
            Long itemId
    ) {
        return ApiResponse.success(shopQueryService.get(itemId));
    }

    /**
     * 선택적인 정수 Query Parameter의 형식과 범위를 검증합니다.
     *
     * @param value 전달받은 문자열
     * @param defaultValue 파라미터 생략 시 사용할 기본값
     * @param min 최솟값
     * @param max 최댓값
     * @param fieldName 오류 안내에 사용할 필드명
     * @return 검증된 정수
     * @throws BusinessException 정수 형식이 아니거나 허용 범위를 벗어난 경우
     */
    private int parseInteger(
            String value,
            int defaultValue,
            int min,
            int max,
            String fieldName
    ) {
        if (value == null) {
            return defaultValue;
        }

        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "는 정수로 입력해 주세요."
            );
        }

        // 범위를 벗어난 값을 자동 보정하지 않고 명세에 따라 거부합니다.
        if (parsed < min || parsed > max) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "의 허용 범위를 확인해 주세요."
            );
        }

        return parsed;
    }
    // 중복·미등록 Query는 기존 StrictQueryParameterInterceptor가 처리
}
