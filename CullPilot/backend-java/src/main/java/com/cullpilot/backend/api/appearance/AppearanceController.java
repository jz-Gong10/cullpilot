package com.cullpilot.backend.api.appearance;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.appearance.AppearanceRequests.SubmitOnboarding;
import com.cullpilot.backend.api.appearance.AppearanceRequests.UpdateAppearance;
import com.cullpilot.backend.api.appearance.AppearanceResponses.CurrentAppearance;
import com.cullpilot.backend.api.appearance.AppearanceResponses.Onboarding;
import com.cullpilot.backend.api.appearance.AppearanceResponses.Recommendation;
import com.cullpilot.backend.service.appearance.AppearanceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/appearance")
public class AppearanceController {

    private final AppearanceService service;

    public AppearanceController(AppearanceService service) { this.service = service; }

    @GetMapping("/onboarding")
    public ResponseEntity<ApiResponse<Onboarding>> onboarding(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(service.questionnaire(authentication.getName())));
    }

    @PostMapping("/onboarding")
    public ResponseEntity<ApiResponse<Recommendation>> submit(
            Authentication authentication, @Valid @RequestBody SubmitOnboarding request) {
        return ResponseEntity.ok(ApiResponse.success(service.submit(authentication.getName(), request)));
    }

    @PostMapping("/onboarding/skip")
    public ResponseEntity<ApiResponse<CurrentAppearance>> skip(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(service.skip(authentication.getName())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CurrentAppearance>> current(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(service.current(authentication.getName())));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<CurrentAppearance>> update(
            Authentication authentication, @Valid @RequestBody UpdateAppearance request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(authentication.getName(), request)));
    }
}
