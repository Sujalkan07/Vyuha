package com.vyuha.assistant;

/**
 * Interaction modes recorded in assistant_interaction_logs.
 */
public enum AssistantMode {
    EXPLAIN_FINDINGS("Explain Findings"),
    WHAT_IF_PREVIEW("What-If Preview"),
    WHAT_IF_EXECUTED("What-If Executed"),
    GUIDELINE_SEARCH("Guideline Search");

    private final String displayName;

    AssistantMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
