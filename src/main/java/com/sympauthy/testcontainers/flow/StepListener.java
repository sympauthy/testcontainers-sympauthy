package com.sympauthy.testcontainers.flow;

/**
 * Observes every step the flow passes through — the "callback at each step". Purely observational
 * (logging, assertions); it does not influence the flow. Register with {@code InteractiveFlow.onStep}.
 */
@FunctionalInterface
public interface StepListener {

    void onStep(FlowStep step);
}
