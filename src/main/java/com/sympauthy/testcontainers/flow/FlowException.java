package com.sympauthy.testcontainers.flow;

/** Thrown when the interactive flow cannot be driven to completion. */
public class FlowException extends RuntimeException {

    public FlowException(String message) {
        super(message);
    }

    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
