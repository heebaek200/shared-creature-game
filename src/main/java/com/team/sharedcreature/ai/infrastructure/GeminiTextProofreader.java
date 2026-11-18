package com.team.sharedcreature.ai.infrastructure;

import com.team.sharedcreature.ai.application.TextProofreader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GeminiTextProofreader implements TextProofreader {

    private static final String GEMINI_API_URL =  "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public GeminiTextProofreader(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper, @Value("${gemini.api-key:}") String apiKey
    ) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @Override
    public String proofread(String text) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API 키가 설정되지 않았습니다.");
        }

        String prompt = """
                   다음 그룹 소개 문장의 맞춤법과 문법을 교정해 주세요.
                   원문의 의미와 의도는 유지해 주세요.
                   설명이나 다른 문장은 작성하지 마세요.
                
                   교정할 문장:
                """ + text;

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "correctedText", Map.of(
                                "type", "STRING",
                                "description", "교정된 그룹 소개 문장"
                        )
                ),
                "required", List.of("correctedText")
        );

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema
                )
        );

        JsonNode response = restClient.post()
                .uri(GEMINI_API_URL)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Gemini 응답이 비어 있습니다");
        }

        String result = response.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text")
                .asText(null);

        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Gemini 교정 결과가 없습니다");
        }

        try {
            JsonNode correctedResponse = objectMapper.readTree(result);
            JsonNode correctedTextNode = correctedResponse.path("correctedText");

            if (!correctedTextNode.isTextual()) {
                throw new IllegalStateException("교정 결과 형식이 올바르지 않습니다.");
            }
            String correctedText = correctedTextNode.asText();
            if (correctedText.isBlank()) {
                throw new IllegalStateException("교정 결과가 비어있습니다");
            }
            if (correctedText.length() > 500) {
                throw new IllegalStateException("교정 결과는 500자 이하여야 합니다.");
            }
            if (correctedText.codePoints().anyMatch(codePoint ->
                    Character.isISOControl(codePoint)
                            && codePoint != '\n'
                            && codePoint != '\r'
                            && codePoint != '\t')) {
                throw new IllegalStateException("교정 결과에 허용되지 않는 문자가 포함되어 있습니다");
            }
            return correctedText;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Gemini 교정 결과를 처리하지 못했습니다.", e);
        }
    }
}
