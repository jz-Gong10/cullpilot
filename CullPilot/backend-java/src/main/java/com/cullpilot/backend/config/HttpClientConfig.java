package com.cullpilot.backend.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient pythonApiRestClient(
            RestClient.Builder builder,
            PythonApiProperties properties) {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.getTimeout());

        return builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
