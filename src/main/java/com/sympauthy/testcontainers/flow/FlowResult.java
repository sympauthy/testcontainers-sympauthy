package com.sympauthy.testcontainers.flow;

import java.util.List;
import java.util.Map;

/**
 * The result of a {@link InteractiveFlow#drive() link-driven} flow — one started from a server-returned
 * step link (e.g. an admin- or client-initiated MFA enrollment) rather than from {@code /authorize}. The
 * flow ends at its one return URL however it ended; {@link #outcome()} reads how from the return's
 * {@code sympauthy_flow_error} parameter. {@link #terminalUrl()} is the URL actually reached and
 * {@link #terminalParam(String)} reads its parameters ({@code sympauthy_flow},
 * {@code sympauthy_flow_error}, {@code sympauthy_return_state}). {@link #stepTypes()} exposes the path
 * taken.
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

    /** How the flow ended, as the return's {@code sympauthy_flow_error} parameter states it. */
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

    /** {@code true} when {@link #outcome()} is {@link FlowOutcome#FAILED}. */
    public boolean isFailed() {
        return outcome == FlowOutcome.FAILED;
    }

    /** The terminal URL the flow actually landed on (the return URL, with its query string). */
    public String terminalUrl() {
        return terminalUrl;
    }

    /**
     * The {@code return_state} the initiating request sent, as the return echoes it in its
     * {@code sympauthy_return_state} parameter, or {@code null} when the return carries none.
     */
    public String returnState() {
        return terminalParams.get("sympauthy_return_state");
    }

    /**
     * A query parameter from the terminal URL (e.g. {@code "sympauthy_flow_error"}), or {@code null} if
     * absent.
     */
    public String terminalParam(String name) {
        return terminalParams.get(name);
    }

    /** The step types this flow traversed, in order (e.g. {@code [CONFIRM, MFA, MFA]}). */
    public List<FlowStep.Type> stepTypes() {
        return stepTypes;
    }
}
