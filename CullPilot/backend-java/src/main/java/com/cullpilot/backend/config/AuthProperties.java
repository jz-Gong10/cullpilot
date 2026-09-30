package com.cullpilot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "security.auth")
public class AuthProperties {

    private Duration accessTokenTtl = Duration.ofHours(24);

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }
}
