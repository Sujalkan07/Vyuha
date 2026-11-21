package com.vyuha.assistant;

/**
 * Default 100% offline deterministic Java AssistantProvider requiring zero external services or API keys.
 */
public class LocalDeterministicAssistantProvider implements AssistantProvider {

    @Override
    public String providerName() {
        return "VYUHA_LOCAL_JAVA";
    }

    @Override
    public boolean isExternal() {
        return false;
    }

    @Override
    public String polishSummary(String systemDirective, String structuredFactsJson, String deterministicSummary) {
        return deterministicSummary != null ? deterministicSummary : "";
    }
}
