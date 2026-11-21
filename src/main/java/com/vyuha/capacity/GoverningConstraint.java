package com.vyuha.capacity;

/**
 * Identifies which constraint produces the tightest preliminary capacity ceiling:
 * C_preliminary = min(C_space, C_flow, C_holding, C_official).
 */
public enum GoverningConstraint {
    USABLE_SPACE("Usable Standing Space (C_space)", "Sum of floor(A_usable * rho_z)"),
    ENTRY_EXIT_FLOW("Entry / Exit Flow (C_flow)", "min(r_entry * T_ingress, r_exit * T_egress)"),
    HOLDING_SPACE("Holding / Queue Space (C_holding)", "Holding area * rho + buffer"),
    OFFICIAL_LIMIT("Official Venue Limit (C_official)", "Statutory / permit cap"),
    INCOMPLETE("Incomplete — Inputs Required", "Define zones and explicit density assumptions");

    private final String displayName;
    private final String formulaSummary;

    GoverningConstraint(String displayName, String formulaSummary) {
        this.displayName = displayName;
        this.formulaSummary = formulaSummary;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFormulaSummary() {
        return formulaSummary;
    }
}
