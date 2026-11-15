package com.sympauthy.testcontainers.flow;

/**
 * The terminal outcome of a {@link InteractiveFlow#drive() link-driven} flow. SympAuthy returns the user
 * to one return URL however the flow ended, and states how in its {@code sympauthy_flow_error} parameter;
 * this is that parameter read as an outcome.
 */
public enum FlowOutcome {

    /** The flow completed: the return carries no {@code sympauthy_flow_error}. */
    SUCCESS,

    /** The user left the flow: {@code sympauthy_flow_error} is {@code auth.interactive_flow_session.cancelled}. */
    CANCELED,

    /** The flow failed or expired: {@code sympauthy_flow_error} carries any other code. */
    FAILED
}
