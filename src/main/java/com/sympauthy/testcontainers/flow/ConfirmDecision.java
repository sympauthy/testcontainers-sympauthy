package com.sympauthy.testcontainers.flow;

/**
 * A {@link ConfirmHandler}'s decision at a {@link FlowStep.Type#CONFIRM confirm} step: whether to
 * approve the pending action or leave the flow. Approving posts to {@code POST /api/v1/flow/confirm};
 * leaving sends the browser to the step's {@link ConfirmFlowResource#cancelUrl() cancel_url}, which
 * redirects it to wherever the session hands the user back. A link-driven run reports the two as
 * {@link FlowOutcome#SUCCESS} and {@link FlowOutcome#CANCELED}.
 */
public enum ConfirmDecision {

    /** Approve the pending action and continue the flow. */
    CONFIRM,

    /**
     * Leave the flow through the step's {@code cancel_url}. Fails the run when the step offers none, since
     * the server then offers no way out of it.
     */
    CANCEL
}
