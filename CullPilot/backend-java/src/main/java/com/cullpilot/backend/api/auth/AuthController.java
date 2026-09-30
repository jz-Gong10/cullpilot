package com.cullpilot.backend.api.auth;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.auth.AuthRequests.LoginRequest;
import com.cullpilot.backend.api.auth.AuthRequests.RegisterRequest;
import com.cullpilot.backend.api.auth.AuthResponses.AuthResponse;
import com.cullpilot.backend.api.auth.AuthResponses.LogoutResponse;
import com.cullpilot.backend.api.auth.AuthResponses.UserResponse;
import com.cullpilot.backend.service.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(authService.me(authentication.getName())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @RequestHeader("Authorization") String authorization) {
        authService.logout(authorization);
        return ResponseEntity.ok(ApiResponse.success(new LogoutResponse(true)));
    }
}
