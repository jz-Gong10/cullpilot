package com.cullpilot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String root = "./storage";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
