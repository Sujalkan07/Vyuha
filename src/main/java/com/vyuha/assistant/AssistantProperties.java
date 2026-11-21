package com.vyuha.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Optional configuration properties for the pluggable AssistantProvider abstraction.
 * Defaults to 100% self-contained deterministic offline execution ("local").
 */
@Configuration
@ConfigurationProperties(prefix = "vyuha.assistant")
public class AssistantProperties {

    private String provider = "local";
    private String endpointUrl = "";
    private String apiKey = "";
    private String model = "";
    private int timeoutMs = 8000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
