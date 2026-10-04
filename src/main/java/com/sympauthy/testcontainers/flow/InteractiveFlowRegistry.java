package com.sympauthy.testcontainers.flow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sympauthy.testcontainers.Client;
import com.sympauthy.testcontainers.client.FlowApiClient;
import com.sympauthy.testcontainers.client.FlowResponse;
import com.sympauthy.testcontainers.client.TokenClient;
import com.sympauthy.testcontainers.internal.json.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mock of SympAuthy's interactive-flow <em>frontend</em>: a small HTTP server that stands in for the
 * pages a user's browser would visit ({@code /sign-in}, {@code /collect-claims}, …) plus the client's
 * {@code redirect_uri} callback. SympAuthy still owns the orchestration — it decides, via the
 * {@code redirect_url} each Flow API call returns, which page comes next — while this server only
 * "renders" each page by invoking the callback of the flow currently running and submitting to the
 * Flow API.
 *
 * <p>One registry hosts one {@code flows.<id>} definition and one client, but any number of
 * {@link InteractiveFlow}s: each is a single scripted run (a sign-up, a sign-in, …) minted with
 * {@link #newFlow()}. They share the registry's pages and client, so you can sign up and then sign in
 * as the same user against the same container. The registry serves whichever flow's {@link
 * InteractiveFlow#run()} is currently executing.
 *
 * <p>Because SympAuthy bakes the flow's page URLs into its configuration at startup, create the
 * registry first (it binds a local port immediately), hand it to the container with
 * {@link com.sympauthy.testcontainers.SympauthyContainer#withFlows(InteractiveFlowRegistry)}, then
 * start the container and {@link InteractiveFlow#run()} each flow:
 *
 * <pre>{@code
 * try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app"))
 *         .withScopes("openid");
 *      SympauthyContainer container = new SympauthyContainer()
 *         .withConfig(passwordAuthAndClaims)
 *         .withFlows(registry)) {
 *     InteractiveFlow signUp = registry.newFlow()
 *             .withSignUpHandler(cfg -> Map.of("email", "ada@example.com", "password", "s3cret"));
 *     InteractiveFlow signIn = registry.newFlow()
 *             .withSignInHandler(cfg -> Credentials.of("ada@example.com", "s3cret"));
 *     container.start();
 *     signUp.run().exchange();                       // creates the user
 *     TokenResponse tokens = signIn.run().exchange(); // signs in as that user
 * }
 * }</pre>
 *
 * <p>Register only the callbacks each flow reaches — each is an independent functional interface. It
 * covers the password happy path (sign-in/sign-up → collect claims → code), the confirm step, and TOTP
 * MFA enrollment/challenge; email/SMS claim validation raises
 * {@link UnsupportedFlowStepException}. The authorize/token endpoints are discovered from
 * {@code /.well-known/openid-configuration}, and the authorization code is captured by the frontend's
 * own {@code /callback} page.
 *
 * <p>A flow that a server-initiated endpoint started (an admin- or client-initiated MFA enrollment)
 * begins at a step link rather than {@code /authorize}: configure it with
 * {@link InteractiveFlow#driveFrom(String, String, String)} and run it with
 * {@link InteractiveFlow#drive()}, which reports whether it reached the success or cancel URL.
 */
public final class InteractiveFlowRegistry implements AutoCloseable {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * The authorization-request parameters {@link #authorizeParams} computes itself — keep the two in sync.
     * They are what let the {@link AuthorizationResult} of a {@link InteractiveFlow#run() run} exchange its
     * code afterwards, so {@link InteractiveFlow#withAuthorizationParam(String, String)} refuses them
     * instead of letting an extra parameter produce a request this registry cannot drive.
     */
    static final Set<String> RESERVED_AUTHORIZATION_PARAMS = Set.of(
            "response_type", "client_id", "redirect_uri", "scope", "state",
            "code_challenge", "code_challenge_method");

    // The client these flows authenticate as — public (PKCE only) or confidential (sends a secret).
    private final Client client;
    private final HttpServer server;
    private final String frontendBaseUrl;

    private String flowId = "default";
    private List<String> scopes = new ArrayList<>();

    private final List<InteractiveFlow> flows = new ArrayList<>();

    // Set by attach(), before run().
    private String baseUrl;
    private String discoveryUrl;

    // Per-run state, written on the mock server's threads and read back on the run() thread. A single
    // flow runs at a time, so the registry can hold it: run(flow) publishes the flow being served and
    // resets the captures before driving the browser.
    private volatile InteractiveFlow active;
    private FlowApiClient api;
    private volatile String capturedCode;
    private volatile String capturedState;
    private volatile RuntimeException failure;

    // Per-run state for a link-driven flow (driveFrom/drive): the terminal reached and its query params.
    private volatile FlowOutcome outcome;
    private volatile String terminalUrl;
    private volatile Map<String, String> terminalParams;

    private InteractiveFlowRegistry(Client client) {
        this.client = client;
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the mock flow frontend server", e);
        }
        this.frontendBaseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/", this::dispatch);
        server.start();
    }

    /**
     * Creates a mock frontend that authenticates as the given {@link Client} — {@link Client#publicClient
     * public} (PKCE only) or {@link Client#confidentialClient confidential} (whose secret the token
     * exchange sends via {@code client_secret_post} or {@code client_secret_basic}). Declare a matching
     * {@code clients.<id>} (and, for a confidential client, {@code clients.<id>.secret}, which
     * {@link #clientSecret()} exposes so the sent and configured secrets stay in sync).
     */
    public static InteractiveFlowRegistry forClient(Client client) {
        return new InteractiveFlowRegistry(client);
    }

    /** The SympAuthy {@code flows.<id>} these flows back (default {@code "default"}). */
    public InteractiveFlowRegistry withFlowId(String flowId) {
        this.flowId = flowId;
        return this;
    }

    public InteractiveFlowRegistry withScopes(String... scopes) {
        this.scopes = Arrays.asList(scopes);
        return this;
    }

    /** Registers a new flow (a single scripted run) on this frontend. Configure its handlers, then {@link InteractiveFlow#run()} it. */
    public InteractiveFlow newFlow() {
        InteractiveFlow flow = new InteractiveFlow(this);
        flows.add(flow);
        return flow;
    }

    /** The {@link Client} these flows authenticate as. */
    public Client client() {
        return client;
    }

    /** The client id these flows authenticate as; configure a matching {@code clients.<id>}. */
    public String clientId() {
        return client.id();
    }

    /**
     * The client secret these flows authenticate with, or {@code null} for a public client. Use it to
     * declare a matching {@code clients.<id>.secret}, keeping the sent and configured secrets in sync.
     */
    public String clientSecret() {
        return client.secret();
    }

    /** The flow id this frontend backs; set your client's {@code authorizationFlow} to it. */
    public String flowId() {
        return flowId;
    }

    /** The redirect URI the mock frontend serves as the client callback; allow it on your client. */
    public String redirectUri() {
        return frontendBaseUrl + "/callback";
    }

    /** The base URL of the mock frontend server; its flow pages live directly under it. */
    public String frontendUrl() {
        return frontendBaseUrl;
    }

    /**
     * The {@code flows.<id>} definition that points SympAuthy at this mock frontend's pages, as flat
     * Micronaut property keys.
     * {@link com.sympauthy.testcontainers.SympauthyContainer#withFlows(InteractiveFlowRegistry)}
     * applies these as program-argument overrides, so they win over any flow config the caller
     * supplied via config files or environment profiles, without disturbing the rest of it.
     *
     * <p>The client is <em>yours</em> to configure: define a {@code clients.<id>} whose id is
     * {@link #clientId()}, whose {@code authorizationFlow} is {@link #flowId()}, and whose
     * {@code allowed-redirect-uris} includes {@link #redirectUri()}.
     */
    public Map<String, String> flowProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        String flow = "flows." + flowId + ".";
        properties.put(flow + "type", "web");
        properties.put(flow + "sign-in", pageUrl("sign-in"));
        properties.put(flow + "sign-up", pageUrl("sign-up"));
        properties.put(flow + "collect-claims", pageUrl("collect-claims"));
        properties.put(flow + "validate-claims", pageUrl("validate-claims"));
        properties.put(flow + "error", pageUrl("error"));
        // The frontend serves the confirm and MFA pages too, so always declare them: confirm is optional
        // server-side, and the four MFA keys are mandatory once MFA is enabled and harmless (unused) when
        // it is not.
        properties.put(flow + "confirm", pageUrl("confirm"));
        properties.put(flow + "mfa-selection-for-enrollment", pageUrl("mfa-selection-for-enrollment"));
        properties.put(flow + "mfa-selection-for-challenge", pageUrl("mfa-selection-for-challenge"));
        properties.put(flow + "mfa-totp-enroll", pageUrl("mfa-totp-enroll"));
        properties.put(flow + "mfa-totp-challenge", pageUrl("mfa-totp-challenge"));
        return properties;
    }

    /** Connects the frontend to a running SympAuthy instance. Called by {@code SympauthyContainer.withFlows}. */
    public void attach(String baseUrl, String discoveryUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.discoveryUrl = discoveryUrl;
    }

    /** Drives one flow to the client callback and returns the captured authorization code. */
    AuthorizationResult run(InteractiveFlow flow) {
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "Registry is not attached to a container; call SympauthyContainer.withFlows(registry) first");
        }
        if (flow.startUrl != null) {
            throw new IllegalStateException(
                    "This flow was configured with driveFrom(...); drive it with drive(), not run()");
        }
        active = flow;
        flow.steps.clear();
        capturedCode = null;
        capturedState = null;
        failure = null;

        HttpClient apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.api = new FlowApiClient(baseUrl, apiClient);

        Map<String, Object> discovery = fetchDiscovery(apiClient);
        String authorizationEndpoint = required(discovery, "authorization_endpoint");
        String tokenEndpoint = required(discovery, "token_endpoint");

        Pkce pkce = Pkce.generate();
        String authorizeUrl = appendQuery(authorizationEndpoint, authorizeParams(randomToken(), pkce));

        // A redirect-following "browser": it rides SympAuthy's 303s across the mock frontend pages
        // until the frontend's /callback captures the code (or the /error page aborts).
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<String> response = send(browser,
                HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build());

        if (failure != null) {
            throw failure;
        }
        if (capturedCode == null) {
            throw new FlowException("Flow did not reach the client callback (ended at " + response.uri()
                    + ", HTTP " + response.statusCode() + ")");
        }
        emit(FlowStep.Type.COMPLETED, Map.of("code", capturedCode));
        TokenClient tokenClient = new TokenClient(tokenEndpoint, apiClient, client);
        return new AuthorizationResult(capturedCode, capturedState, redirectUri(), pkce.codeVerifier,
                tokenClient);
    }

    /**
     * Drives a {@link InteractiveFlow#driveFrom(String, String, String) link-driven} flow from its start
     * link to a terminal. Skips {@code /authorize}/PKCE/token: the flow was already started by a
     * server-initiated endpoint, so this only follows the browser from {@code startUrl} until it reaches
     * the flow's success or cancel URL.
     */
    FlowResult drive(InteractiveFlow flow) {
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "Registry is not attached to a container; call SympauthyContainer.withFlows(registry) first");
        }
        if (flow.startUrl == null) {
            throw new IllegalStateException("Call driveFrom(startUrl, successUrl, cancelUrl) before drive()");
        }
        requireInternal("start", flow.startUrl);
        requireInternal("success", flow.successUrl);
        requireInternal("cancel", flow.cancelUrl);

        active = flow;
        flow.steps.clear();
        outcome = null;
        terminalUrl = null;
        terminalParams = null;
        failure = null;

        HttpClient apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.api = new FlowApiClient(baseUrl, apiClient);

        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<String> response = send(browser,
                HttpRequest.newBuilder(URI.create(flow.startUrl)).GET().build());

        if (failure != null) {
            throw failure;
        }
        if (outcome == null) {
            throw new FlowException("Flow did not reach the success or cancel URL (ended at " + response.uri()
                    + ", HTTP " + response.statusCode() + ")");
        }
        return new FlowResult(outcome, terminalUrl, terminalParams, flow.stepTypes());
    }

    /** Ensures a driveFrom URL belongs to this frontend, so the browser is actually served by it. */
    private void requireInternal(String role, String url) {
        if (url == null || !url.startsWith(frontendBaseUrl + "/")) {
            throw new IllegalArgumentException("The " + role + " URL must be a page of this mock frontend ("
                    + frontendBaseUrl + "); got: " + url);
        }
    }

    /** Stops the mock frontend server. */
    @Override
    public void close() {
        server.stop(0);
    }

    // --- mock frontend pages (run on the HTTP server's threads) ---

    private void dispatch(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String state = queryParam(query, "state");
        String name = path.startsWith("/") ? path.substring(1) : path;
        try {
            // A link-driven flow ends when the browser lands on its success or cancel URL, whatever the path.
            InteractiveFlow current = active;
            if (current != null && current.startUrl != null && captureTerminal(exchange, current, path, query)) {
                return;
            }
            switch (name) {
                case "sign-in", "sign-up" -> respondRedirect(exchange, authenticatePage(state));
                case "confirm" -> respondRedirect(exchange, confirmPage(state));
                case "collect-claims" -> respondRedirect(exchange, collectClaimsPage(state));
                case "validate-claims" -> respondRedirect(exchange, validateClaimsPage(state));
                case "mfa-selection-for-enrollment" -> respondRedirect(exchange, mfaEnrollmentSelectionPage(state));
                case "mfa-totp-enroll" -> respondRedirect(exchange, totpEnrollPage(state));
                case "mfa-selection-for-challenge" -> respondRedirect(exchange, mfaChallengeSelectionPage(state));
                case "mfa-totp-challenge" -> respondRedirect(exchange, totpChallengePage(state));
                case "callback" -> {
                    capturedCode = queryParam(query, "code");
                    capturedState = queryParam(query, "state");
                    respond(exchange, 200, "ok");
                }
                case "error" -> {
                    failure = new FlowException("Flow was redirected to the error page (" + query + ")");
                    respond(exchange, 200, "error");
                }
                default -> {
                    failure = new UnsupportedFlowStepException(
                            "SympAuthy redirected to a flow page this frontend does not support: " + path);
                    respond(exchange, 404, "unsupported flow page");
                }
            }
        } catch (RuntimeException e) {
            failure = e;
            safeRespond(exchange, 500);
        } catch (Exception e) {
            failure = new FlowException("Mock flow page failed", e);
            safeRespond(exchange, 500);
        }
    }

    private String authenticatePage(String state) {
        if (active.signInHandler != null) {
            FlowResponse response = api.getSignIn(state);
            if (response.redirectUrl() != null) {
                throw new FlowException("sign-in step is not available for this attempt "
                        + "(server redirected to " + response.redirectUrl() + ")");
            }
            Credentials credentials = active.signInHandler.signIn(SignInFlowResource.fromMap(response.body()));
            emit(FlowStep.Type.SIGN_IN, response.body());
            return requireRedirect(api.signIn(state, credentials.login(), credentials.password()), "sign-in");
        }
        if (active.signUpHandler != null) {
            FlowResponse response = api.getSignUp(state);
            if (response.redirectUrl() != null) {
                throw new FlowException("sign-up step is not available for this attempt "
                        + "(server redirected to " + response.redirectUrl() + ")");
            }
            Map<String, Object> fields = active.signUpHandler.signUp(SignUpFlowResource.fromMap(response.body()));
            emit(FlowStep.Type.SIGN_UP, response.body());
            return requireRedirect(api.signUp(state, fields), "sign-up");
        }
        throw new FlowException(
                "No authentication handler: call withSignInHandler(...) or withSignUpHandler(...)");
    }

    private String collectClaimsPage(String state) {
        FlowResponse response = api.getClaims(state);
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // nothing to collect: auto-skip
        }
        List<Claim> requested = claimsOf(response.body());
        emit(FlowStep.Type.CLAIMS, response.body());
        if (active.claimsHandler == null) {
            throw new UnsupportedFlowStepException("Flow reached the collect-claims page with "
                    + requested.size() + " claim(s) but no withClaimsHandler(...) was configured");
        }
        return requireRedirect(api.postClaims(state, active.claimsHandler.provide(requested)), "claims");
    }

    private String validateClaimsPage(String state) {
        emit(FlowStep.Type.VALIDATION, Map.of());
        throw new UnsupportedFlowStepException(
                "Flow requires claim validation, which the v1 driver does not automate");
    }

    private String confirmPage(String state) {
        FlowResponse response = api.getConfirm(state);
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // already confirmed: continue to the next step
        }
        ConfirmFlowResource resource = ConfirmFlowResource.fromMap(response.body());
        emit(FlowStep.Type.CONFIRM, response.body());
        if (active.confirmHandler == null) {
            throw new UnsupportedFlowStepException("Flow reached the confirm page (action "
                    + resource.action() + ") but no withConfirmHandler(...) was configured");
        }
        if (active.confirmHandler.decide(resource) == ConfirmDecision.CANCEL) {
            emit(FlowStep.Type.CANCEL, Map.of());
            return requireRedirect(api.cancel(state), "cancel");
        }
        return requireRedirect(api.confirm(state), "confirm");
    }

    private String mfaEnrollmentSelectionPage(String state) {
        return mfaSelectionPage(api.getMfaEnrollment(state), "enrollment");
    }

    private String mfaChallengeSelectionPage(String state) {
        return mfaSelectionPage(api.getMfaChallenge(state), "challenge");
    }

    private String mfaSelectionPage(FlowResponse response, String phase) {
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // not skippable: auto-redirect to the single method
        }
        Object skip = response.get("skip_redirect_url");
        if (skip != null) {
            return skip.toString(); // skippable (optional MFA during sign-up): skip by default
        }
        String totp = totpMethodRedirect(response.body());
        if (totp == null) {
            throw new UnsupportedFlowStepException("MFA " + phase
                    + " offered no TOTP method the driver can handle: " + response.body());
        }
        return totp;
    }

    private String totpEnrollPage(String state) {
        TotpEnrollData data = TotpEnrollData.fromMap(api.getTotpEnrollData(state).body());
        emit(FlowStep.Type.MFA, data.raw());
        String code = active.totpEnrollmentHandler != null
                ? active.totpEnrollmentHandler.code(data)
                : Totp.code(data.secret());
        return requireRedirect(api.confirmTotpEnrollment(state, code), "totp-enroll");
    }

    private String totpChallengePage(String state) {
        if (active.totpChallengeHandler == null) {
            throw new UnsupportedFlowStepException(
                    "Flow reached the TOTP challenge page but no withTotpChallengeHandler(...) was configured");
        }
        emit(FlowStep.Type.MFA, Map.of());
        return requireRedirect(api.submitTotpChallenge(state, active.totpChallengeHandler.code()), "totp-challenge");
    }

    // --- helpers ---

    private boolean captureTerminal(HttpExchange exchange, InteractiveFlow flow, String path, String query)
            throws IOException {
        FlowOutcome reached;
        if (path.equals(pathOf(flow.successUrl))) {
            reached = FlowOutcome.SUCCESS;
        } else if (path.equals(pathOf(flow.cancelUrl))) {
            reached = FlowOutcome.CANCELED;
        } else {
            return false;
        }
        outcome = reached;
        terminalUrl = query == null ? path : path + "?" + query;
        terminalParams = queryParams(query);
        respond(exchange, 200, "ok");
        return true;
    }

    private static String totpMethodRedirect(Map<String, Object> body) {
        if (body.get("methods") instanceof List<?> methods) {
            for (Object element : methods) {
                if (element instanceof Map<?, ?> method
                        && "TOTP".equals(String.valueOf(method.get("method")))) {
                    Object url = method.get("redirect_url");
                    return url == null ? null : url.toString();
                }
            }
        }
        return null;
    }

    private static String pathOf(String url) {
        return URI.create(url).getPath();
    }

    private static Map<String, String> queryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), value);
        }
        return params;
    }

    private Map<String, Object> fetchDiscovery(HttpClient httpClient) {
        HttpResponse<String> response = send(httpClient, HttpRequest.newBuilder(URI.create(discoveryUrl))
                .header("Accept", "application/json").GET().build());
        if (response.statusCode() != 200) {
            throw new FlowException("Discovery document returned HTTP " + response.statusCode());
        }
        return JsonCodec.parseObject(response.body());
    }

    private Map<String, String> authorizeParams(String oauthState, Pkce pkce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", client.id());
        params.put("redirect_uri", redirectUri());
        params.put("scope", String.join(" ", scopes));
        params.put("state", oauthState);
        params.put("code_challenge", pkce.codeChallenge);
        params.put("code_challenge_method", pkce.method);
        if (active.invitationToken != null) {
            params.put("invitation_token", active.invitationToken);
        }
        if (active.nonce != null) {
            params.put("nonce", active.nonce);
        }
        // Caller-supplied extras last, so they win over the two parameters that also have a named setter.
        // The reserved ones above are refused at withAuthorizationParam(...), so nothing here can break
        // the request this run must be able to exchange afterwards.
        params.putAll(active.authorizationParams);
        return params;
    }

    private void emit(FlowStep.Type type, Map<String, Object> data) {
        FlowStep step = new FlowStep(type, data);
        active.steps.add(step);
        if (active.stepListener != null) {
            active.stepListener.onStep(step);
        }
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FlowException("HTTP call to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("HTTP call interrupted", e);
        }
    }

    private static String requireRedirect(FlowResponse response, String step) {
        String url = response.redirectUrl();
        if (url == null) {
            throw new FlowException(step + " page got no redirect_url (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        return url;
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new FlowException("Discovery document is missing '" + key + "'");
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Claim> claimsOf(Map<String, Object> body) {
        List<Claim> claims = new ArrayList<>();
        if (body.get("claims") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> claimMap) {
                    claims.add(Claim.fromMap((Map<String, Object>) claimMap));
                }
            }
        }
        return claims;
    }

    /** The mock frontend URL for a named page. */
    private String pageUrl(String name) {
        return frontendBaseUrl + "/" + name;
    }

    private void respondRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        respond(exchange, 303, null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void safeRespond(HttpExchange exchange, int status) {
        try {
            respond(exchange, status, "flow error");
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) {
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String appendQuery(String base, Map<String, String> params) {
        StringBuilder builder = new StringBuilder(base).append(base.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
