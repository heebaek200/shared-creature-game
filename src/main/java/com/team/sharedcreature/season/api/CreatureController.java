package com.team.sharedcreature.season.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.season.application.CreatureHomeQueryService;
import com.team.sharedcreature.season.application.CreatureSelectionService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 크리처 홈 Snapshot과 관리자 종 선택 REST 요청을 처리합니다. */
@RestController
@RequestMapping("/api/v1")
public class CreatureController {

    private final CreatureHomeQueryService homeQueryService;
    private final CreatureSelectionService selectionService;

    /** 크리처 조회와 선택 Service를 주입받습니다. */
    public CreatureController(
            CreatureHomeQueryService homeQueryService,
            CreatureSelectionService selectionService
    ) {
        this.homeQueryService = homeQueryService;
        this.selectionService = selectionService;
    }

    /** 주·부 관리자가 선택 가능한 활성 크리처 종을 조회합니다. */
    @GetMapping("/creature-species")
    public ApiResponse<List<CreatureSpeciesResponse>> listActiveCreatureSpecies(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        return ApiResponse.success(selectionService.listActiveSpecies(loginUser.id()));
    }

    /** 현재 사용자의 그룹 크리처 홈 Snapshot을 조회합니다. */
    @GetMapping("/groups/me/creature-home")
    public ApiResponse<CreatureHomeResponse> getCreatureHome(
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        return ApiResponse.success(homeQueryService.getCreatureHome(loginUser.id()));
    }

    /** 주·부 관리자가 부화 기한 전 시작 크리처 종을 선택하거나 변경합니다. */
    @PutMapping("/groups/me/seasons/current/creature-selection")
    public ApiResponse<CreatureSelectionResponse> selectCreatureSpecies(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CreatureSelectionRequest request
    ) {
        return ApiResponse.success(selectionService.selectSpecies(loginUser.id(), request.creatureSpeciesId()));
    }
}
