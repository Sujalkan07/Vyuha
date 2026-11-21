package com.vyuha.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the active AssistantProvider (offline deterministic Java engine by default).
 */
@Configuration
public class AssistantConfig {

    @Bean
    public AssistantProvider assistantProvider(AssistantProperties properties, ObjectMapper objectMapper) {
        if (properties != null
                && "openai-compatible".equalsIgnoreCase(properties.getProvider())
                && properties.getEndpointUrl() != null
                && !properties.getEndpointUrl().isBlank()) {
            return new OpenAiCompatibleAssistantProvider(properties, objectMapper);
        }
        return new LocalDeterministicAssistantProvider();
    }
}
