package com.cullpilot.backend.api;

public record ApiErrorResponse(ApiError error, ApiMeta meta) {
}
