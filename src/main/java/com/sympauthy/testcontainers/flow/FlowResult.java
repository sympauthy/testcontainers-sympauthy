package com.sympauthy.testcontainers.flow;

import java.util.List;
import java.util.Map;

/**
 * The result of a {@link InteractiveFlow#drive() link-driven} flow — one started from a server-returned
 * step link (e.g. an admin- or client-initiated MFA enrollment) rather than from {@code /authorize}. It
 * reports whether the flow ended at the expected success URL or the expected cancel URL
 * ({@link #outcome()}), the terminal URL actually reached, and any query parameters on it (e.g. an
 * {@code error} for an authorization-code cancellation). {@link #stepTypes()} exposes the path taken.
 *
 * <p>Unlike {@link AuthorizationResult}, there is no authorization code to exchange: a
 * {@code PLAIN}-redirect flow hands control back to the caller's URL directly.
 */
public final class FlowResult {

    private final FlowOutcome outcome;
    private final String terminalUrl;
    private final Map<String, String> terminalParams;
    private final List<FlowStep.Type> stepTypes;

    FlowResult(FlowOutcome outcome, String terminalUrl, Map<String, String> terminalParams,
            List<FlowStep.Type> stepTypes) {
        this.outcome = outcome;
        this.terminalUrl = terminalUrl;
        this.terminalParams = Map.copyOf(terminalParams);
        this.stepTypes = List.copyOf(stepTypes);
    }

    /** Whether the flow reached the success URL or the cancel URL. */
    public FlowOutcome outcome() {
        return outcome;
    }

    /** {@code true} when {@link #outcome()} is {@link FlowOutcome#SUCCESS}. */
    public boolean isSuccess() {
        return outcome == FlowOutcome.SUCCESS;
    }

    /** {@code true} when {@link #outcome()} is {@link FlowOutcome#CANCELED}. */
    public boolean isCanceled() {
        return outcome == FlowOutcome.CANCELED;
    }

    /** The terminal URL the flow actually landed on (the success or cancel URL, with its query string). */
    public String terminalUrl() {
        return terminalUrl;
    }

    /** A query parameter from the terminal URL (e.g. {@code "error"}), or {@code null} if absent. */
    public String terminalParam(String name) {
        return terminalParams.get(name);
    }

    /** The step types this flow traversed, in order (e.g. {@code [CONFIRM, MFA, MFA]}). */
    public List<FlowStep.Type> stepTypes() {
        return stepTypes;
    }
}
