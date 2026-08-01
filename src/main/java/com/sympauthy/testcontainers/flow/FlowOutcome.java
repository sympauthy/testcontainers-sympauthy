package com.sympauthy.testcontainers.flow;

/**
 * The terminal outcome of a {@link InteractiveFlow#drive() link-driven} flow: whether the user reached
 * the expected success URL or the expected cancel URL.
 */
public enum FlowOutcome {

    /** The flow reached the expected success (return) URL. */
    SUCCESS,

    /** The flow reached the expected cancel URL. */
    CANCELED
}
