package com.cullpilot.backend.api;

import java.util.UUID;

public record ApiResponse<T>(T data, ApiMeta meta) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, new ApiMeta(UUID.randomUUID().toString()));
    }
}
