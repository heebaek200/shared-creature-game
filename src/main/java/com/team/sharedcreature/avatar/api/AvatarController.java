package com.team.sharedcreature.avatar.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.avatar.application.AvatarService;
import com.team.sharedcreature.avatar.application.AvatarCommandRetryService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 로그인한 사용자의 보유 아이템과 아바타 조회 요청을 처리합니다.
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class AvatarController {

    private final AvatarService avatarService;
    private final AvatarCommandRetryService avatarCommandRetryService;

    /**
     * 아바타 조회 Service와 변경 재시도 Service를 주입받습니다.
     *
     * @param avatarService             보유 아이템·아바타 조회 Service
     * @param avatarCommandRetryService 장착·해제 재시도 Service
     */
    public AvatarController(
            AvatarService avatarService,
            AvatarCommandRetryService avatarCommandRetryService
    ) {
        this.avatarService = avatarService;
        this.avatarCommandRetryService = avatarCommandRetryService;
    }

    /**
     * 본인의 보유 아이템을 지정한 아바타 슬롯에 장착합니다.
     *
     * @param loginUser 세션에서 확인한 로그인 사용자
     * @param slot      장착할 슬롯
     * @param request   보유 아이템 ID와 최초 사용 동의 여부
     * @return 변경된 아바타의 전체 슬롯 정보
     */
    @PutMapping("/avatar/equipments/{slot}")
    public ApiResponse<AvatarResponse> equipAvatarItem(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "slot")
            @Pattern(
                    regexp = "HEAD|FACE|BODY|ACCESSORY",
                    message = "슬롯은 HEAD, FACE, BODY, ACCESSORY 중 하나여야 합니다."
            )
            String slot,
            @Valid @RequestBody EquipAvatarItemRequest request
    ) {
        // DB 동시성 오류가 발생하면 별도 트랜잭션으로 제한적으로 재시도합니다.
        AvatarResponse response = avatarCommandRetryService.equipAvatarItem(
                loginUser.id(),
                slot,
                request.ownedItemId(),
                request.confirmFirstUse()
        );

        return ApiResponse.success(response);
    }

    /**
     * 본인 아바타의 지정한 슬롯에서 아이템을 해제합니다.
     *
     * @param loginUser 세션에서 확인한 로그인 사용자
     * @param slot      해제할 슬롯
     * @return 변경된 아바타의 전체 슬롯 정보
     */
    @DeleteMapping("/avatar/equipments/{slot}")
    public ApiResponse<AvatarResponse> unequipAvatarItem(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable(name = "slot")
            @Pattern(
                    regexp = "HEAD|FACE|BODY|ACCESSORY",
                    message = "슬롯은 HEAD, FACE, BODY, ACCESSORY 중 하나여야 합니다."
            )
            String slot
    ) {
        // 장착과 동일한 재시도 정책으로 해제 요청을 처리합니다.
        AvatarResponse response = avatarCommandRetryService.unequipAvatarItem(
                loginUser.id(),
                slot
        );

        return ApiResponse.success(response);
    }

    /**
     * 로그인한 사용자의 보유 아이템 목록을 조회합니다.
     *
     * @param loginUser 세션에서 확인한 로그인 사용자
     * @param page      페이지 번호, 생략하면 0
     * @param size      페이지 크기, 생략하면 20
     * @param slot      슬롯 필터, 생략하면 전체
     * @param status    보유 상태 필터, 생략하면 전체
     * @return 보유 아이템 목록과 페이지 정보
     * @throws BusinessException 페이지 번호·크기가 올바르지 않은 경우
     */
    @GetMapping("/owned-items")
    public ApiResponse<List<OwnedItemResponse>> listOwnedItems(
            @AuthenticationPrincipal LoginUser loginUser, // AuthenticationPrincipal : 인증된 사용자 정보를 받습니다.
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "slot", required = false)
            @Pattern(
                    regexp = "HEAD|FACE|BODY|ACCESSORY",
                    message = "슬롯은 HEAD, FACE, BODY, ACCESSORY 중 하나여야 합니다."
            )
            String slot,
            @RequestParam(name = "status", required = false)
            @Pattern(
                    regexp = "OWNED_UNUSED|OWNED_USED|REVOKED",
                    message = "보유 상태는 OWNED_UNUSED, OWNED_USED, REVOKED 중 하나여야 합니다."
            )
            String status
    ) {
        // 생략한 값에만 기본값을 적용하고 빈 문자열이나 범위 초과는 거부합니다.
        int pageNumber = parseInteger(page, 0, 0, Integer.MAX_VALUE, "page");
        int pageSize = parseInteger(size, 20, 1, 50, "size");

        // 사용자 ID는 요청값이 아닌 인증된 세션 정보에서 가져옵니다.
        Page<OwnedItemResponse> result = avatarService.listOwnedItems(
                loginUser.id(),
                pageNumber,
                pageSize,
                slot,
                status
        );

        return ApiResponse.page(result);
    }

    /**
     * 로그인한 사용자의 현재 아바타를 조회합니다.
     *
     * @param loginUser 세션에서 확인한 로그인 사용자
     * @return 네 슬롯의 장착 정보를 담은 공통 응답
     */
    @GetMapping("/avatar")
    public ApiResponse<AvatarResponse> getAvatar(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        return ApiResponse.success(
                avatarService.getAvatar(loginUser.id())
        );
    }

    /**
     * 선택적인 정수 Query Parameter의 형식과 범위를 검증합니다.
     *
     * @param value        전달받은 문자열
     * @param defaultValue 파라미터 생략 시 기본값
     * @param min          최솟값
     * @param max          최댓값
     * @param fieldName    오류 안내에 사용할 필드명
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

        // 잘못된 값을 자동 보정하지 않고 입력 오류로 반환합니다.
        if (parsed < min || parsed > max) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    fieldName + "의 허용 범위를 확인해 주세요."
            );
        }

        return parsed;
    }
}
