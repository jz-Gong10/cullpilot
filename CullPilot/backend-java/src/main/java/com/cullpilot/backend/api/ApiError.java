package com.cullpilot.backend.api;

public record ApiError(String code, String message, Object details) {
}
