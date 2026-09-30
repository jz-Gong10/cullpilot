package com.cullpilot.backend.security;

import com.cullpilot.backend.api.ApiError;
import com.cullpilot.backend.api.ApiErrorResponse;
import com.cullpilot.backend.api.ApiMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ApiErrorResponse(
                new ApiError("ACCESS_DENIED", "没有权限访问该资源", null),
                new ApiMeta(UUID.randomUUID().toString()))));
    }
}
