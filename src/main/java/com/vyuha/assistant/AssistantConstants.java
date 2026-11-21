package com.vyuha.assistant;

/**
 * Non-negotiable attribution and boundary constants enforced across every Phase 11 assistant output.
 */
public final class AssistantConstants {

    public static final String ATTRIBUTION_TAG = "AI/Assistant Note — Human Review Required";

    public static final String READ_ONLY_NOTICE =
            "Read-only decision support: reads verified Java engine outputs and never modifies venue geometry, "
            + "zones, infrastructure, or pathways without explicit confirmation.";

    public static final String GUIDELINE_DISCLAIMER =
            "Guidance excerpts are provided for planning reference only and do not replace statutory permits, "
            + "local authority orders, or human professional safety review.";

    private AssistantConstants() {
    }
}
