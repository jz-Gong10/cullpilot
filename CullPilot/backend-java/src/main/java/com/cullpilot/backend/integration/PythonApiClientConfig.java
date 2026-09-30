package com.cullpilot.backend.integration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonApiClientConfig {

    @Bean
    public PythonApiClient pythonApiClient(RestClient pythonApiRestClient) {
        return new PythonApiClient(pythonApiRestClient);
    }
}

