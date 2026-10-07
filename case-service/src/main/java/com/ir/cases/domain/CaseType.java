package com.ir.cases.domain;

public enum CaseType {
    MEDICAL("MEDICAL"),
    SECURITY("SECURITY"),
    EVACUATION("EVACUATION"),
    TRAVEL_ASSIST("LOGISTICS");

    private final String requiredSkill;

    CaseType(String requiredSkill) {
        this.requiredSkill = requiredSkill;
    }

    /** Responder skill needed to handle this case type. */
    public String requiredSkill() {
        return requiredSkill;
    }
}
