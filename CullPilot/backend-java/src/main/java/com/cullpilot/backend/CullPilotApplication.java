package com.cullpilot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.cullpilot.backend.config.PythonApiProperties;
import com.cullpilot.backend.config.StorageProperties;
import com.cullpilot.backend.config.AuthProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({
        PythonApiProperties.class,
        StorageProperties.class,
        AuthProperties.class})
public class CullPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CullPilotApplication.class, args);
    }
}
