package com.team.sharedcreature.ai.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature.ai.application.TextProofreadingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TextProofreadingController {

    private final TextProofreadingService textProofreadingService;

    public TextProofreadingController(TextProofreadingService textProofreadingService) {
        this.textProofreadingService = textProofreadingService;
    }

    public record ProofreadingRequest(
            @NotBlank(message = "그룹 소개를 입력해 주세요.")
            String text
    ) {
    }

    @PostMapping("/api/v1/ai/proofreads")
    public ApiResponse<TextProofreadingService.ProofreadingResult> proofread(
            @Valid @RequestBody ProofreadingRequest request,
            @AuthenticationPrincipal LoginUser loginUser
    ) {
        return ApiResponse.success(textProofreadingService.proofread(loginUser.id(), request.text()));
    }
}
