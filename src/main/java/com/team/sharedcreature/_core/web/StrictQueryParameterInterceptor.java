package com.team.sharedcreature._core.web;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * API 메서드가 선언하지 않은 Query Parameter와 중복 Query 입력을 거부합니다.
 */
@Component
public class StrictQueryParameterInterceptor implements HandlerInterceptor {

    /** Controller 호출 전에 Query 이름과 중복 여부를 검증합니다. */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String query = request.getQueryString();
        if (query == null || query.isBlank() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Set<String> allowed = allowedParameters(handlerMethod);
        Map<String, Integer> supplied = suppliedParameters(query);
        boolean invalid = supplied.entrySet().stream()
                .anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue() > 1);
        if (invalid) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "Query Parameter를 확인해 주세요."
            );
        }
        return true;
    }

    private Set<String> allowedParameters(HandlerMethod handlerMethod) {
        Set<String> allowed = new HashSet<>();
        Arrays.stream(handlerMethod.getMethodParameters()).forEach(parameter -> {
            RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
            if (annotation == null) {
                return;
            }
            String name = annotation.name().isBlank() ? annotation.value() : annotation.name();
            allowed.add(name.isBlank() ? parameter.getParameterName() : name);
        });
        return allowed;
    }

    private Map<String, Integer> suppliedParameters(String query) {
        Map<String, Integer> supplied = new HashMap<>();
        Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2)[0])
                .map(this::decode)
                .forEach(name -> supplied.merge(name, 1, Integer::sum));
        return supplied;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "Query Parameter를 확인해 주세요.");
        }
    }
}
