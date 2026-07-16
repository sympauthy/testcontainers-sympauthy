package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * A single step observed while the flow runs, delivered to a {@link StepListener} at every
 * transition so a test can log or assert on progress. {@link #data()} is the raw JSON body of the
 * step (empty for purely synthetic transitions).
 */
public record FlowStep(Type type, Map<String, Object> data) {

    /** The kind of step reached. */
    public enum Type {
        CONFIGURATION,
        SIGN_IN,
        SIGN_UP,
        MFA,
        CLAIMS,
        VALIDATION,
        COMPLETED
    }
}
