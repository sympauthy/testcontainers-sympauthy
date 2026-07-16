package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.SympauthyContainer;

import java.io.IOException;
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
 * Drives SympAuthy's interactive authorization flow end to end from a test: it starts at the OAuth
 * authorize endpoint, walks each step invoking the per-step callbacks you register, and returns the
 * authorization code — which {@link AuthorizationResult#exchange()} turns into tokens.
 *
 * <p>Register only the callbacks the flow needs; each is an independent functional interface, so a
 * password sign-up that collects no extra claims needs only {@link #onSignUp(SignUpHandler)}:
 *
 * <pre>{@code
 * TokenResponse tokens = InteractiveFlow.against(container)
 *         .clientId("test-app")
 *         .redirectUri("http://localhost/callback")
 *         .scopes("openid", "profile", "email")
 *         .onSignUp(cfg -> Map.of("email", "ada@example.com", "password", "s3cret"))
 *         .onStep(step -> System.out.println(step.type()))   // optional: fires at every step
 *         .run()
 *         .exchange();
 * }</pre>
 *
 * <p>v1 automates the password happy path (configuration → sign-in/sign-up → collect claims →
 * authorization code). Steps it does not yet drive (MFA, enforced email/SMS validation) are
 * auto-skipped when the server allows it and raise {@link UnsupportedFlowStepException} otherwise.
 * The endpoints are discovered from the server's {@code /.well-known/openid-configuration}, not
 * hard-coded.
 */
public final class InteractiveFlow {

    private static final int MAX_STEPS = 20;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String discoveryUrl;
    private final HttpClient httpClient;
    private final FlowApiClient api;

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private List<String> scopes = new ArrayList<>();

    private SignInHandler signInHandler;
    private SignUpHandler signUpHandler;
    private ClaimsHandler claimsHandler;
    private ValidationCodeHandler validationCodeHandler;
    private StepListener stepListener;

    private InteractiveFlow(String baseUrl, String discoveryUrl) {
        String base = stripTrailingSlash(baseUrl);
        this.discoveryUrl = discoveryUrl;
        // Redirect following is disabled so the authorize redirect (and any others) can be inspected
        // rather than followed. Flow API steps return their next hop as a JSON redirect_url, so no
        // HTTP redirect is ever auto-followed.
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.api = new FlowApiClient(base, httpClient);
    }

    /** Targets the SympAuthy instance running in the given container. */
    public static InteractiveFlow against(SympauthyContainer container) {
        return new InteractiveFlow(container.getBaseUrl(), container.getOpenIdConfigurationUrl());
    }

    /** Targets a SympAuthy instance at an arbitrary base URL. */
    public static InteractiveFlow at(String baseUrl) {
        return new InteractiveFlow(baseUrl,
                stripTrailingSlash(baseUrl) + "/.well-known/openid-configuration");
    }

    public InteractiveFlow clientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public InteractiveFlow clientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
        return this;
    }

    public InteractiveFlow redirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
        return this;
    }

    public InteractiveFlow scopes(String... scopes) {
        this.scopes = Arrays.asList(scopes);
        return this;
    }

    public InteractiveFlow onSignIn(SignInHandler handler) {
        this.signInHandler = handler;
        return this;
    }

    public InteractiveFlow onSignUp(SignUpHandler handler) {
        this.signUpHandler = handler;
        return this;
    }

    public InteractiveFlow onClaims(ClaimsHandler handler) {
        this.claimsHandler = handler;
        return this;
    }

    public InteractiveFlow onValidationCode(ValidationCodeHandler handler) {
        this.validationCodeHandler = handler;
        return this;
    }

    public InteractiveFlow onStep(StepListener listener) {
        this.stepListener = listener;
        return this;
    }

    /** The underlying thin Flow API client, for custom calls outside the managed run. */
    public FlowApiClient api() {
        return api;
    }

    /** Drives the flow to the client redirect and returns the authorization code. */
    public AuthorizationResult run() {
        requireConfigured();
        Map<String, Object> discovery = fetchDiscovery();
        String authorizationEndpoint = required(discovery, "authorization_endpoint");
        String tokenEndpoint = required(discovery, "token_endpoint");

        Pkce pkce = Pkce.generate();
        String oauthState = randomToken();
        String flowState = startAuthorization(authorizationEndpoint, oauthState, pkce);

        FlowResponse configResponse = api.getConfiguration(flowState);
        FlowConfiguration configuration = FlowConfiguration.fromMap(configResponse.body());
        emit(FlowStep.Type.CONFIGURATION, configResponse.body());

        String redirectUrl = authenticate(configuration, flowState);

        int steps = 0;
        while (true) {
            if (steps++ > MAX_STEPS) {
                throw new FlowException("Flow did not reach the client redirect after " + MAX_STEPS + " steps");
            }
            if (targetsRedirectUri(redirectUrl)) {
                String code = queryParam(redirectUrl, "code");
                if (code == null) {
                    throw new FlowException("Client redirect carried no authorization code: " + redirectUrl);
                }
                emit(FlowStep.Type.COMPLETED, Map.of("redirect_url", redirectUrl));
                return new AuthorizationResult(code, queryParam(redirectUrl, "state"), tokenEndpoint,
                        redirectUri, clientId, clientSecret, pkce.codeVerifier, httpClient);
            }
            redirectUrl = advance(redirectUrl);
        }
    }

    private String authenticate(FlowConfiguration configuration, String flowState) {
        if (signInHandler != null) {
            Credentials credentials = signInHandler.signIn(configuration);
            emit(FlowStep.Type.SIGN_IN, Map.of("login", credentials.login()));
            return requireRedirect(api.signIn(flowState, credentials.login(), credentials.password()), "sign-in");
        }
        if (signUpHandler != null) {
            Map<String, Object> fields = signUpHandler.signUp(configuration);
            emit(FlowStep.Type.SIGN_UP, Map.of());
            return requireRedirect(api.signUp(flowState, fields), "sign-up");
        }
        throw new FlowException("No authentication handler configured: call onSignIn(...) or onSignUp(...)");
    }

    /** Handles one intermediate step (given its {@code redirect_url}) and returns the next one. */
    private String advance(String redirectUrl) {
        String state = queryParam(redirectUrl, "state");
        String path = pathOf(redirectUrl);
        if (path.contains("/claims/validation")) {
            return handleValidation(redirectUrl, state, path);
        }
        if (path.endsWith("/claims")) {
            return handleClaims(state);
        }
        if (path.contains("/mfa")) {
            return handleMfa(state);
        }
        // Unknown intermediate endpoint: GET it and expect it to point onwards.
        FlowResponse response = api.getUrl(redirectUrl);
        String next = response.redirectUrl();
        if (next == null) {
            throw new UnsupportedFlowStepException("Unhandled flow step at " + redirectUrl + ": " + response.body());
        }
        return next;
    }

    private String handleClaims(String state) {
        FlowResponse response = api.getClaims(state);
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // nothing to collect: auto-skip
        }
        List<Claim> requested = claimsOf(response.body());
        emit(FlowStep.Type.CLAIMS, response.body());
        if (claimsHandler == null) {
            throw new UnsupportedFlowStepException("Flow reached the collect-claims step with "
                    + requested.size() + " claim(s) but no onClaims(...) handler was configured");
        }
        return requireRedirect(api.postClaims(state, claimsHandler.provide(requested)), "claims");
    }

    private String handleValidation(String redirectUrl, String state, String path) {
        String media = mediaOf(path);
        FlowResponse response = media == null ? api.getUrl(redirectUrl) : api.getValidation(state, media);
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // nothing to validate: auto-skip
        }
        emit(FlowStep.Type.VALIDATION, response.body());
        if (validationCodeHandler == null || media == null) {
            throw new UnsupportedFlowStepException("Flow requires "
                    + (media == null ? "claim" : media) + " validation, which the v1 driver does not automate");
        }
        return requireRedirect(api.postValidation(state, media, validationCodeHandler.provide(media)), "validation");
    }

    private String handleMfa(String state) {
        FlowResponse response = api.getMfa(state);
        if (response.redirectUrl() != null) {
            return response.redirectUrl(); // MFA not required / auto-routed: follow
        }
        emit(FlowStep.Type.MFA, response.body());
        throw new UnsupportedFlowStepException("Flow requires MFA, which the v1 driver does not automate");
    }

    private String startAuthorization(String authorizationEndpoint, String oauthState, Pkce pkce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", String.join(" ", scopes));
        params.put("state", oauthState);
        params.put("code_challenge", pkce.codeChallenge);
        params.put("code_challenge_method", pkce.method);

        HttpResponse<String> response = sendRaw(HttpRequest.newBuilder(
                URI.create(appendQuery(authorizationEndpoint, params))).GET().build());
        if (response.statusCode() / 100 != 3) {
            throw new FlowException("Authorize endpoint did not redirect (HTTP " + response.statusCode()
                    + "). Body: " + brief(response.body()));
        }
        String location = response.headers().firstValue("location")
                .orElseThrow(() -> new FlowException("Authorize redirect had no Location header"));
        String flowState = queryParam(location, "state");
        if (flowState == null) {
            throw new FlowException("Could not extract the flow state from the authorize redirect: " + location);
        }
        return flowState;
    }

    private Map<String, Object> fetchDiscovery() {
        HttpResponse<String> response = sendRaw(HttpRequest.newBuilder(URI.create(discoveryUrl))
                .header("Accept", "application/json").GET().build());
        if (response.statusCode() != 200) {
            throw new FlowException("Discovery document returned HTTP " + response.statusCode());
        }
        return JsonCodec.parseObject(response.body());
    }

    private HttpResponse<String> sendRaw(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FlowException("HTTP call to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("HTTP call interrupted", e);
        }
    }

    private void emit(FlowStep.Type type, Map<String, Object> data) {
        if (stepListener != null) {
            stepListener.onStep(new FlowStep(type, data));
        }
    }

    private void requireConfigured() {
        if (clientId == null) {
            throw new IllegalStateException("clientId(...) is required");
        }
        if (redirectUri == null) {
            throw new IllegalStateException("redirectUri(...) is required");
        }
    }

    private boolean targetsRedirectUri(String redirectUrl) {
        return redirectUrl.startsWith(redirectUri);
    }

    private static String requireRedirect(FlowResponse response, String step) {
        String url = response.redirectUrl();
        if (url == null) {
            throw new FlowException(step + " step returned no redirect_url (HTTP "
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

    /** The path of a URL that may be absolute or root-relative, excluding any query string. */
    private static String pathOf(String url) {
        String withoutQuery = url;
        int query = withoutQuery.indexOf('?');
        if (query >= 0) {
            withoutQuery = withoutQuery.substring(0, query);
        }
        if (withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://")) {
            return URI.create(withoutQuery).getPath();
        }
        return withoutQuery;
    }

    /** The media segment of a {@code .../claims/validation/<media>} path, or {@code null} if absent. */
    private static String mediaOf(String path) {
        String marker = "/claims/validation/";
        int index = path.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String rest = path.substring(index + marker.length());
        if (rest.isEmpty()) {
            return null;
        }
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String queryParam(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        String queryString = url.substring(query + 1);
        int fragment = queryString.indexOf('#');
        if (fragment >= 0) {
            queryString = queryString.substring(0, fragment);
        }
        for (String pair : queryString.split("&")) {
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
        StringBuilder builder = new StringBuilder(base);
        builder.append(base.contains("?") ? '&' : '?');
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

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
