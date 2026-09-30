package com.cullpilot.backend.security;

import com.cullpilot.backend.api.ApiError;
import com.cullpilot.backend.api.ApiErrorResponse;
import com.cullpilot.backend.api.ApiMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ApiErrorResponse(
                new ApiError("AUTHENTICATION_REQUIRED", "请先登录", null),
                new ApiMeta(UUID.randomUUID().toString()))));
    }
}
