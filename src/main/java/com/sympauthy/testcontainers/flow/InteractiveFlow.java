package com.sympauthy.testcontainers.flow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // When set, sent as the invitation_token query parameter on the authorize request that starts this
    // run, redeeming a (bootstrap) invitation as part of the sign-up.
    String invitationToken;

    // When set, sent as the nonce query parameter on the authorize request that starts this run; the
    // issued id_token must echo it back unchanged (OpenID Connect Core replay mitigation).
    String nonce;

    // Steps traversed during run(), appended by the registry's emit() on the server threads and read
    // back on the run()/test thread — hence a thread-safe list.
    final List<FlowStep> steps = new CopyOnWriteArrayList<>();

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

    /**
     * Redeems an invitation on this run: {@code token} is sent as the {@code invitation_token} query
     * parameter on the authorize request, binding the invitation to the sign-up. Use it with a
     * {@link #withSignUpHandler(SignUpHandler) sign-up handler} to register the invited user — e.g. the
     * first admin created from a bootstrap invitation (see
     * {@link com.sympauthy.testcontainers.SympauthyContainer#getBootstrapInvitationToken(String)}).
     *
     * @param token the raw invitation token
     * @return this flow, for chaining
     */
    public InteractiveFlow withInvitationToken(String token) {
        this.invitationToken = token;
        return this;
    }

    /**
     * Sets the OpenID Connect {@code nonce} for this run: {@code nonce} is sent as the {@code nonce}
     * query parameter on the authorize request, and the issued {@code id_token} must carry the same
     * value back unchanged (OpenID Connect Core 1.0 §3.1.3.7 / §15.5.2 — a replay mitigation). Read it
     * back from the {@code id_token} obtained via {@link AuthorizationResult#exchange()}
     * ({@link TokenResponse#idToken()} / {@link TokenResponse#raw()}). When unset, no {@code nonce}
     * parameter is sent.
     *
     * @param nonce the nonce value to echo through the flow
     * @return this flow, for chaining
     */
    public InteractiveFlow withNonce(String nonce) {
        this.nonce = nonce;
        return this;
    }

    /** Drives this flow to the client callback and returns the captured authorization code. */
    public AuthorizationResult run() {
        return registry.run(this);
    }

    /**
     * The step types this flow traversed, in order, for its most recent {@link #run()} — e.g.
     * {@code [SIGN_UP, COMPLETED]}. Lets a test assert on the path taken without wiring up a
     * {@link StepListener}; the listener remains for reacting to a step as it happens or reading its
     * {@link FlowStep#data()}.
     */
    public List<FlowStep.Type> stepTypes() {
        return steps.stream().map(FlowStep::type).toList();
    }
}
