package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * Supplies the field map for the password sign-up step — typically {@code password} plus the
 * configured identifier claims (e.g. {@code email}). Register with
 * {@code InteractiveFlow.withSignUpHandler}.
 */
@FunctionalInterface
public interface SignUpHandler {

    Map<String, Object> signUp(FlowConfiguration configuration);
}
