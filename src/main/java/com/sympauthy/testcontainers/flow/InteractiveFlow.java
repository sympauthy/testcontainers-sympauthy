package com.sympauthy.testcontainers.flow;

/**
 * A single scripted run of SympAuthy's interactive flow — a sign-up, a sign-in, … — served by an
 * {@link InteractiveFlowRegistry}. Mint one with {@link InteractiveFlowRegistry#newFlow()}, register
 * only the callbacks this run reaches, then {@link #run()} it once the container has started.
 *
 * <p>The registry owns the mock frontend server, the {@code flows.<id>} configuration and the client;
 * a flow carries only its handlers. While {@link #run()} executes, the registry serves this flow's
 * pages by invoking these handlers.
 *
 * <pre>{@code
 * InteractiveFlow signUp = registry.newFlow()
 *         .withSignUpHandler(cfg -> Map.of("email", "ada@example.com", "password", "s3cret"));
 * signUp.run().exchange();
 * }</pre>
 */
public final class InteractiveFlow {

    private final InteractiveFlowRegistry registry;

    // Read by the registry's server threads while this flow is the one being run.
    SignInHandler signInHandler;
    SignUpHandler signUpHandler;
    ClaimsHandler claimsHandler;
    ValidationCodeHandler validationCodeHandler;
    StepListener stepListener;

    InteractiveFlow(InteractiveFlowRegistry registry) {
        this.registry = registry;
    }

    public InteractiveFlow withSignInHandler(SignInHandler handler) {
        this.signInHandler = handler;
        return this;
    }

    public InteractiveFlow withSignUpHandler(SignUpHandler handler) {
        this.signUpHandler = handler;
        return this;
    }

    public InteractiveFlow withClaimsHandler(ClaimsHandler handler) {
        this.claimsHandler = handler;
        return this;
    }

    public InteractiveFlow withValidationCodeHandler(ValidationCodeHandler handler) {
        this.validationCodeHandler = handler;
        return this;
    }

    public InteractiveFlow withStepListener(StepListener listener) {
        this.stepListener = listener;
        return this;
    }

    /** Drives this flow to the client callback and returns the captured authorization code. */
    public AuthorizationResult run() {
        return registry.run(this);
    }
}
