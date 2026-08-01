package com.sympauthy.testcontainers.flow;

/**
 * Decides whether to approve or cancel the action presented at a {@link FlowStep.Type#CONFIRM confirm}
 * step, given the {@link ConfirmFlowResource} describing it. Return {@link ConfirmDecision#CONFIRM} to
 * approve and continue, or {@link ConfirmDecision#CANCEL} to cancel the flow. Register with
 * {@code InteractiveFlow.withConfirmHandler}.
 */
@FunctionalInterface
public interface ConfirmHandler {

    ConfirmDecision decide(ConfirmFlowResource resource);
}
