package com.sympauthy.testcontainers.flow;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A thin client over SympAuthy's <a href="https://sympauthy.github.io/technical/api/flow.html">Flow
 * API</a>: one method per endpoint, each returning the parsed {@link FlowResponse}. It encapsulates
 * the Flow API's state-transport rules: GET requests carry the flow state as a {@code ?state=} query
 * parameter, POST requests carry it in an {@code Authorization: State <jwt>} header.
 *
 * <p>Use this directly to drive custom or partial flows; {@link InteractiveFlow} builds on it to run
 * the whole flow with per-step callbacks.
 */
public final class FlowApiClient {

    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * @param baseUrl    the server base URL, e.g. {@code http://localhost:49172}
     * @param httpClient the HTTP client to use (redirect following should be disabled)
     */
    public FlowApiClient(String baseUrl, HttpClient httpClient) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.httpClient = httpClient;
    }

    public FlowResponse getConfiguration(String state) {
        return get("/api/v1/flow/configuration", state);
    }

    public FlowResponse signIn(String state, String login, String password) {
        return post("/api/v1/flow/sign-in", state, Map.of("login", login, "password", password));
    }

    public FlowResponse signUp(String state, Map<String, ?> fields) {
        return post("/api/v1/flow/sign-up", state, fields);
    }

    public FlowResponse getClaims(String state) {
        return get("/api/v1/flow/claims", state);
    }

    public FlowResponse postClaims(String state, Map<String, ?> values) {
        return post("/api/v1/flow/claims", state, values);
    }

    public FlowResponse getMfa(String state) {
        return get("/api/v1/flow/mfa", state);
    }

    public FlowResponse getValidation(String state, String media) {
        return get("/api/v1/flow/claims/validation/" + media, state);
    }

    public FlowResponse postValidation(String state, String media, String code) {
        return post("/api/v1/flow/claims/validation", state, Map.of("media", media, "code", code));
    }

    /** GETs an arbitrary flow URL as returned in a {@code redirect_url} (absolute or root-relative). */
    public FlowResponse getUrl(String url) {
        return send(HttpRequest.newBuilder(resolve(url)).header("Accept", "application/json").GET().build());
    }

    private FlowResponse get(String path, String state) {
        URI uri = resolve(path + "?state=" + encode(state));
        return send(HttpRequest.newBuilder(uri).header("Accept", "application/json").GET().build());
    }

    private FlowResponse post(String path, String state, Map<String, ?> body) {
        HttpRequest request = HttpRequest.newBuilder(resolve(path))
                .header("Authorization", "State " + state)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)))
                .build();
        return send(request);
    }

    private FlowResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new FlowResponse(response.statusCode(), parseBody(response.body()));
        } catch (IOException e) {
            throw new FlowException("Flow API call to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("Flow API call interrupted", e);
        }
    }

    private static Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        Object parsed = JsonCodec.parse(body);
        if (parsed instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        }
        return Map.of("value", parsed);
    }

    private URI resolve(String urlOrPath) {
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            return URI.create(urlOrPath);
        }
        return URI.create(baseUrl + (urlOrPath.startsWith("/") ? "" : "/") + urlOrPath);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
