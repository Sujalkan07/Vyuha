package com.vyuha.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional env-configured OpenAI-compatible / Ollama summary formatter with strict safety-wording filter
 * and automatic fallback to LocalDeterministicAssistantProvider on any failure.
 */
public class OpenAiCompatibleAssistantProvider implements AssistantProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAssistantProvider.class);

    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LocalDeterministicAssistantProvider fallback = new LocalDeterministicAssistantProvider();

    public OpenAiCompatibleAssistantProvider(AssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeoutMs = Math.max(1000, properties.getTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public String providerName() {
        return "OPENAI_COMPATIBLE_EXTERNAL";
    }

    @Override
    public boolean isExternal() {
        return true;
    }

    @Override
    public String polishSummary(String systemDirective, String structuredFactsJson, String deterministicSummary) {
        if (properties.getEndpointUrl() == null || properties.getEndpointUrl().isBlank()) {
            return fallback.polishSummary(systemDirective, structuredFactsJson, deterministicSummary);
        }
        try {
            String endpoint = properties.getEndpointUrl().trim();
            if (!endpoint.endsWith("/chat/completions")) {
                endpoint = endpoint.endsWith("/") ? endpoint + "chat/completions" : endpoint + "/chat/completions";
            }
            String model = (properties.getModel() != null && !properties.getModel().isBlank())
                    ? properties.getModel().trim()
                    : "llama3.1";

            String guardedSystem = (systemDirective != null ? systemDirective : "")
                    + " Rules: Use ONLY the provided numeric facts verbatim. Never invent numbers or declare a venue SAFE, CERTIFIED, or STAMPEDE-PROOF.";
            String userContent = "Verified Java Facts JSON:\n" + structuredFactsJson
                    + "\n\nBaseline Summary:\n" + deterministicSummary;

            Map<String, Object> payload = Map.of(
                    "model", model,
                    "temperature", 0.1,
                    "messages", List.of(
                            Map.of("role", "system", "content", guardedSystem),
                            Map.of("role", "user", "content", userContent)
                    )
            );
            String body = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(1000, properties.getTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + properties.getApiKey().trim());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("External assistant endpoint returned HTTP {}; falling back to local deterministic summary.", response.statusCode());
                return fallback.polishSummary(systemDirective, structuredFactsJson, deterministicSummary);
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank() || containsForbiddenCertificationLanguage(content)) {
                return fallback.polishSummary(systemDirective, structuredFactsJson, deterministicSummary);
            }
            return content.trim();
        } catch (Exception ex) {
            log.warn("External assistant provider failed ({}); falling back to local deterministic summary.", ex.getMessage());
            return fallback.polishSummary(systemDirective, structuredFactsJson, deterministicSummary);
        }
    }

    private boolean containsForbiddenCertificationLanguage(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("STAMPEDE-PROOF")
                || upper.contains("APPROVED BY VYUHA")
                || upper.contains("CERTIFIED SAFE")
                || upper.contains("VENUE IS SAFE");
    }
}
