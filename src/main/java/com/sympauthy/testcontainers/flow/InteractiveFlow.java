package com.sympauthy.testcontainers.flow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

/**
 * A mock of SympAuthy's interactive-flow <em>frontend</em>: a small HTTP server that stands in for the
 * pages a user's browser would visit ({@code /sign-in}, {@code /collect-claims}, …) plus the client's
 * {@code redirect_uri} callback. SympAuthy still owns the orchestration — it decides, via the
 * {@code redirect_url} each Flow API call returns, which page comes next — while this server only
 * "renders" each page by invoking the callback you registered and submitting to the Flow API.
 *
 * <p>Because SympAuthy bakes the flow's page URLs into its configuration at startup, create the flow
 * first (it binds a local port immediately), hand it to the container with
 * {@link com.sympauthy.testcontainers.SympauthyContainer#withFlow(InteractiveFlow)}, then start the
 * container and {@link #run()}:
 *
 * <pre>{@code
 * try (InteractiveFlow flow = InteractiveFlow.forClient("test-app")
 *         .withScopes("openid")
 *         .withSignUpHandler(cfg -> Map.of("email", "ada@example.com", "password", "s3cret"));
 *      SympauthyContainer container = new SympauthyContainer()
 *         .withConfig(passwordAuthAndClaims)
 *         .withFlow(flow)) {
 *     container.start();
 *     TokenResponse tokens = flow.run().exchange();
 * }
 * }</pre>
 *
 * <p>Register only the callbacks the flow reaches — each is an independent functional interface. v1
 * covers the password happy path (sign-in/sign-up → collect claims → code); enforced MFA and
 * email/SMS validation raise {@link UnsupportedFlowStepException}. The authorize/token endpoints are
 * discovered from {@code /.well-known/openid-configuration}, and the authorization code is captured
 * by the frontend's own {@code /callback} page.
 */
public final class InteractiveFlow implements AutoCloseable {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String clientId;
    private final HttpServer server;
    private final String frontendBaseUrl;

    private String flowId = "default";
    private List<String> scopes = new ArrayList<>();

    private SignInHandler signInHandler;
    private SignUpHandler signUpHandler;
    private ClaimsHandler claimsHandler;
    private ValidationCodeHandler validationCodeHandler;
    private StepListener stepListener;

    // Set by attach(), before run().
    private String baseUrl;
    private String discoveryUrl;

    // Per-run state, written on the mock server's threads and read back on the run() thread.
    private FlowApiClient api;
    private volatile String capturedCode;
    private volatile String capturedState;
    private volatile RuntimeException failure;

    private InteractiveFlow(String clientId) {
        this.clientId = clientId;
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the mock flow frontend server", e);
        }
        this.frontendBaseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/", this::dispatch);
        server.start();
    }

    /** Creates a mock frontend that will authenticate as the given client id. */
    public static InteractiveFlow forClient(String clientId) {
        return new InteractiveFlow(clientId);
    }

    /** The SympAuthy {@code flows.<id>} this frontend backs (default {@code "default"}). */
    public InteractiveFlow withFlowId(String flowId) {
        this.flowId = flowId;
        return this;
    }

    public InteractiveFlow withScopes(String... scopes) {
        this.scopes = Arrays.asList(scopes);
        return this;
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

    /** The client id this flow authenticates as. */
    public String clientId() {
        return clientId;
    }

    /** The redirect URI the mock frontend serves as the client callback. */
    public String redirectUri() {
        return frontendBaseUrl + "/callback";
    }

    /** The base URL of the mock frontend server; its flow pages live directly under it. */
    public String frontendUrl() {
        return frontendBaseUrl;
    }

    /**
     * The {@code clients.<id>} + {@code flows.<id>} configuration that points SympAuthy at this mock
     * frontend, as flat Micronaut property keys (list elements use {@code [i]} indices).
     * {@link com.sympauthy.testcontainers.SympauthyContainer#withFlow(InteractiveFlow)} applies these
     * as program-argument overrides, so they win over any client/flow config the caller supplied via
     * config files or environment profiles, without disturbing the rest of it.
     */
    public Map<String, String> containerProperties() {
        Map<String, String> properties = new LinkedHashMap<>();

        String client = "clients." + clientId + ".";
        properties.put(client + "public", "true");
        properties.put(client + "authorizationFlow", flowId);
        properties.put(client + "allowed-grant-types[0]", "authorization_code");
        for (int i = 0; i < scopes.size(); i++) {
            properties.put(client + "allowed-scopes[" + i + "]", scopes.get(i));
        }
        properties.put(client + "allowed-redirect-uris[0]", redirectUri());

        String flow = "flows." + flowId + ".";
        properties.put(flow + "type", "web");
        properties.put(flow + "sign-in", pageUrl("sign-in"));
        properties.put(flow + "sign-up", pageUrl("sign-up"));
        properties.put(flow + "collect-claims", pageUrl("collect-claims"));
        properties.put(flow + "validate-claims", pageUrl("validate-claims"));
        properties.put(flow + "error", pageUrl("error"));

        return properties;
    }

    /** Connects this flow to a running SympAuthy instance. Called by {@code SympauthyContainer.withFlow}. */
    public void attach(String baseUrl, String discoveryUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.discoveryUrl = discoveryUrl;
    }

    /** Drives the flow to the client callback and returns the captured authorization code. */
    public AuthorizationResult run() {
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "Flow is not attached to a container; call SympauthyContainer.withFlow(flow) first");
        }
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
        return new AuthorizationResult(capturedCode, capturedState, tokenEndpoint, redirectUri(),
                clientId, null, pkce.codeVerifier, apiClient);
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
            switch (name) {
                case "sign-in", "sign-up" -> respondRedirect(exchange, authenticatePage(state));
                case "collect-claims" -> respondRedirect(exchange, collectClaimsPage(state));
                case "validate-claims" -> respondRedirect(exchange, validateClaimsPage(state));
                case "callback" -> {
                    capturedCode = queryParam(query, "code");
                    capturedState = queryParam(query, "state");
                    respond(exchange, 200, "ok");
                }
                case "error" -> {
                    failure = new FlowException("Flow was redirected to the error page (" + query + ")");
                    respond(exchange, 200, "error");
                }
                default -> respond(exchange, 404, "unknown flow page");
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
        FlowResponse configResponse = api.getConfiguration(state);
        FlowConfiguration configuration = FlowConfiguration.fromMap(configResponse.body());
        if (signInHandler != null) {
            Credentials credentials = signInHandler.signIn(configuration);
            emit(FlowStep.Type.SIGN_IN, configResponse.body());
            return requireRedirect(api.signIn(state, credentials.login(), credentials.password()), "sign-in");
        }
        if (signUpHandler != null) {
            Map<String, Object> fields = signUpHandler.signUp(configuration);
            emit(FlowStep.Type.SIGN_UP, configResponse.body());
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
        if (claimsHandler == null) {
            throw new UnsupportedFlowStepException("Flow reached the collect-claims page with "
                    + requested.size() + " claim(s) but no withClaimsHandler(...) was configured");
        }
        return requireRedirect(api.postClaims(state, claimsHandler.provide(requested)), "claims");
    }

    private String validateClaimsPage(String state) {
        emit(FlowStep.Type.VALIDATION, Map.of());
        throw new UnsupportedFlowStepException(
                "Flow requires claim validation, which the v1 driver does not automate");
    }

    // --- helpers ---

    private Map<String, Object> fetchDiscovery(HttpClient client) {
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(URI.create(discoveryUrl))
                .header("Accept", "application/json").GET().build());
        if (response.statusCode() != 200) {
            throw new FlowException("Discovery document returned HTTP " + response.statusCode());
        }
        return JsonCodec.parseObject(response.body());
    }

    private Map<String, String> authorizeParams(String oauthState, Pkce pkce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri());
        params.put("scope", String.join(" ", scopes));
        params.put("state", oauthState);
        params.put("code_challenge", pkce.codeChallenge);
        params.put("code_challenge_method", pkce.method);
        return params;
    }

    private void emit(FlowStep.Type type, Map<String, Object> data) {
        if (stepListener != null) {
            stepListener.onStep(new FlowStep(type, data));
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
