package com.vyuha.assistant;

/**
 * Pluggable text-polishing provider abstraction over deterministic Java safety facts.
 * Providers may never invent numeric metrics, alter geometry, or emit safety certification claims.
 */
public interface AssistantProvider {

    String providerName();

    boolean isExternal();

    String polishSummary(String systemDirective, String structuredFactsJson, String deterministicSummary);
}
