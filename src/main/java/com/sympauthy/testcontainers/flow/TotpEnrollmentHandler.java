package com.sympauthy.testcontainers.flow;

/**
 * Supplies the code that confirms a TOTP enrollment, given the {@link TotpEnrollData} the server
 * returned. By default the flow computes a valid code from the secret automatically, so a handler is
 * only needed to observe the secret (e.g. to keep it for a later challenge) or to deliberately submit a
 * wrong code. Register with {@code InteractiveFlow.withTotpEnrollmentHandler}.
 */
@FunctionalInterface
public interface TotpEnrollmentHandler {

    String code(TotpEnrollData data);
}
