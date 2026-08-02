package com.sympauthy.testcontainers.flow;

/**
 * Supplies the code that answers a TOTP challenge (the second factor asked of an already-enrolled user
 * during sign-in). There is no default: the flow cannot know the secret unless a test captured it at
 * enrollment, so compute the code with {@link Totp#code(String)} from that secret. Register with
 * {@code InteractiveFlow.withTotpChallengeHandler}.
 */
@FunctionalInterface
public interface TotpChallengeHandler {

    String code();
}
