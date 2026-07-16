package com.sympauthy.testcontainers.flow;

/** Supplies credentials for the password sign-in step. Register with {@code InteractiveFlow.onSignIn}. */
@FunctionalInterface
public interface SignInHandler {

    Credentials signIn(FlowConfiguration configuration);
}
