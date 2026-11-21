package com.vyuha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Storage configuration for local uploaded supporting documents and later phase exports.
 */
@Configuration
@ConfigurationProperties(prefix = "vyuha.storage")
public class StorageConfig {

    private String uploadDir = "./data/uploads";

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }
}
