package com.vyuha.common;

import java.time.Instant;
import java.util.List;

/**
 * Standard JSON envelope for REST endpoints and structured error responses.
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        List<String> details,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, List.of(), Instant.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data, List.of(), Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null, List.of(), Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message, List<String> details) {
        return new ApiResponse<>(false, code, message, null, details, Instant.now());
    }
}
