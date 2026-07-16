package com.sympauthy.testcontainers.flow;

/**
 * Thrown when the flow reaches a step this driver does not yet automate (MFA or enforced email/SMS
 * validation), or a step for which no handler was configured. The v1 driver covers the password
 * happy path; other steps are auto-skipped when the server allows it and raised otherwise.
 */
public class UnsupportedFlowStepException extends FlowException {

    public UnsupportedFlowStepException(String message) {
        super(message);
    }
}
