package com.team.sharedcreature.ai.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TextProofreadingController {

    public record ProofreadingRequest(
            @NotBlank(message = "그룹 소개를 입력해 주세요.")
            @Size(max = 500, message = "그룹 소개는 500자 이하여야 합니다.")
            String text
    ) {
    }

    @PostMapping("/api/v1/ai/proofread")
    public void validateRequest(@Valid @RequestBody ProofreadingRequest request
    ) {
    }
}
