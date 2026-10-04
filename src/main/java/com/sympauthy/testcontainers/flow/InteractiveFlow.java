package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.client.TokenResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    ConfirmHandler confirmHandler;
    ClaimsHandler claimsHandler;
    ValidationCodeHandler validationCodeHandler;
    TotpEnrollmentHandler totpEnrollmentHandler;
    TotpChallengeHandler totpChallengeHandler;
    StepListener stepListener;

    // When set, sent as the invitation_token query parameter on the authorize request that starts this
    // run, redeeming a (bootstrap) invitation as part of the sign-up.
    String invitationToken;

    // When set, sent as the nonce query parameter on the authorize request that starts this run; the
    // issued id_token must echo it back unchanged (OpenID Connect Core replay mitigation).
    String nonce;

    // Extra query parameters for the authorize request that starts this run, set by
    // withAuthorizationParam(...) — the escape hatch for parameters this module does not model. Applied
    // after the ones the registry computes, which withAuthorizationParam refuses to let a caller set.
    final Map<String, String> authorizationParams = new LinkedHashMap<>();

    // Set by driveFrom(...) for a link-driven run: the server-returned step link to start from, and the
    // success/cancel URLs to recognize as terminals. All must belong to the mock frontend.
    String startUrl;
    String successUrl;
    String cancelUrl;

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

    /** Decides whether to approve or cancel the action at a {@link FlowStep.Type#CONFIRM confirm} step. */
    public InteractiveFlow withConfirmHandler(ConfirmHandler handler) {
        this.confirmHandler = handler;
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

    /**
     * Observes (or overrides) the code used to confirm TOTP enrollment. By default the flow computes a
     * valid code from the secret automatically; set this to capture the secret (e.g. for a later
     * challenge) or to submit a wrong code.
     */
    public InteractiveFlow withTotpEnrollmentHandler(TotpEnrollmentHandler handler) {
        this.totpEnrollmentHandler = handler;
        return this;
    }

    /** Supplies the code that answers a TOTP challenge during sign-in of an already-enrolled user. */
    public InteractiveFlow withTotpChallengeHandler(TotpChallengeHandler handler) {
        this.totpChallengeHandler = handler;
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

    /**
     * Adds an arbitrary query parameter to the authorization request that starts this run — the escape
     * hatch for an {@code /authorize} parameter this module does not model ({@code claims},
     * {@code max_age}, {@code prompt}, {@code login_hint}, {@code acr_values}, …). Call it once per
     * parameter; the value is url-encoded, so one needing it — a JSON object, a space-separated list —
     * reaches the server intact. The run stays an ordinary {@link #run()}: the driver still generates the
     * PKCE pair and the {@link AuthorizationResult} still {@link AuthorizationResult#exchange() exchanges}.
     *
     * <p>The parameters the driver computes — {@code response_type}, {@code client_id},
     * {@code redirect_uri}, {@code scope}, {@code state}, {@code code_challenge} and
     * {@code code_challenge_method} — are what make that exchange possible, so setting one is refused
     * rather than honoured; a test that needs one of them malformed has to issue the authorization
     * request itself. {@code nonce} and {@code invitation_token} may be set here, but
     * {@link #withNonce(String)} and {@link #withInvitationToken(String)} spell them better; set both
     * ways, the value given here wins.
     *
     * @param name  the query parameter name
     * @param value the value, unencoded
     * @return this flow, for chaining
     * @throws IllegalArgumentException if {@code name} is blank or names a parameter the driver computes,
     *                                  or if {@code value} is {@code null}
     */
    public InteractiveFlow withAuthorizationParam(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Authorization parameter name must not be blank");
        }
        if (InteractiveFlowRegistry.RESERVED_AUTHORIZATION_PARAMS.contains(name)) {
            throw new IllegalArgumentException("Authorization parameter '" + name + "' is computed by the"
                    + " flow driver and cannot be overridden; issue the authorization request yourself if a"
                    + " test needs to change it");
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "Value of authorization parameter '" + name + "' must not be null");
        }
        authorizationParams.put(name, value);
        return this;
    }

    /** Drives this flow to the client callback and returns the captured authorization code. */
    public AuthorizationResult run() {
        return registry.run(this);
    }

    /**
     * Configures a <em>link-driven</em> run: instead of starting at {@code /authorize}, this flow starts
     * at {@code startUrl} — a step link a server-initiated flow handed back (e.g. the {@code redirect_url}
     * from an admin- or client-initiated MFA enrollment) — and ends when it reaches {@code successUrl} or
     * {@code cancelUrl}. Drive it with {@link #drive()}, which returns a {@link FlowResult} reporting which
     * terminal was reached.
     *
     * <p>All three URLs must belong to this registry's mock frontend (they are verified against its
     * internal base URL); {@code successUrl}/{@code cancelUrl} are the {@code return_uri}/{@code cancel_uri}
     * you supplied to the initiating endpoint and registered as the client's redirect URIs.
     *
     * @param startUrl   the step link to start the browser at
     * @param successUrl the URL that marks a successful completion
     * @param cancelUrl  the URL that marks a cancellation
     * @return this flow, for chaining
     */
    public InteractiveFlow driveFrom(String startUrl, String successUrl, String cancelUrl) {
        this.startUrl = startUrl;
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
        return this;
    }

    /**
     * Drives a {@link #driveFrom(String, String, String) link-driven} flow from its start link to a
     * terminal and returns the {@link FlowResult} (SUCCESS or CANCELED). Call {@link #driveFrom} first.
     */
    public FlowResult drive() {
        return registry.drive(this);
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
