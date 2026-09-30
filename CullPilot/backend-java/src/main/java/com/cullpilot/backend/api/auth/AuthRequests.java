package com.cullpilot.backend.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    private AuthRequests() {
    }

    public record RegisterRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 254, message = "邮箱长度不能超过 254 个字符")
            String email,
            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 72, message = "密码长度必须在 8 到 72 个字符之间")
            String password,
            @Size(max = 100, message = "显示名称不能超过 100 个字符")
            String displayName) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=<redacted>, displayName="
                    + displayName + "]";
        }
    }

    public record LoginRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            String email,
            @NotBlank(message = "密码不能为空")
            String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }
}
