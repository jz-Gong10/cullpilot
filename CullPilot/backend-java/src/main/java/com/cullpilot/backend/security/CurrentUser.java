package com.cullpilot.backend.security;

import com.cullpilot.backend.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "请先登录");
        }
        return authentication.getName();
    }
}
