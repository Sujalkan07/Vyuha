package com.vyuha.venue;

/**
 * Site plan starting workspace modes supported in Phase 3.
 */
public enum WorkspaceMode {
    INTERACTIVE_MAP("Interactive Vector Map"),
    BLUEPRINT_UPLOAD("Blueprint & Photo Upload");

    private final String displayName;

    WorkspaceMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
