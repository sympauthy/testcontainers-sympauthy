package com.sympauthy.testcontainers.flow;

/**
 * A {@link ConfirmHandler}'s decision at a {@link FlowStep.Type#CONFIRM confirm} step: whether to
 * approve the pending action or cancel the flow. The two map to different Flow API endpoints
 * ({@code POST /api/v1/flow/confirm} vs {@code POST /api/v1/flow/cancel}) and different terminals
 * ({@link FlowOutcome#SUCCESS} vs {@link FlowOutcome#CANCELED}).
 */
public enum ConfirmDecision {

    /** Approve the pending action and continue the flow. */
    CONFIRM,

    /** Cancel the flow, sending the user to the cancellation redirect. */
    CANCEL
}
