package com.sympauthy.testcontainers.flow;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of driving the flow to the client redirect: the authorization {@link #code()} (and the
 * OAuth {@code state} echoed back), plus {@link #exchange()} to trade the code for tokens at the
 * token endpoint using the authorization-code grant and the PKCE verifier from this run.
 */
public final class AuthorizationResult {

    private final String code;
    private final String state;
    private final String tokenEndpoint;
    private final String redirectUri;
    private final String clientId;
    private final String clientSecret;
    private final String codeVerifier;
    private final HttpClient httpClient;

    AuthorizationResult(
            String code,
            String state,
            String tokenEndpoint,
            String redirectUri,
            String clientId,
            String clientSecret,
            String codeVerifier,
            HttpClient httpClient) {
        this.code = code;
        this.state = state;
        this.tokenEndpoint = tokenEndpoint;
        this.redirectUri = redirectUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.codeVerifier = codeVerifier;
        this.httpClient = httpClient;
    }

    /** The authorization code returned on the client redirect. */
    public String code() {
        return code;
    }

    /** The OAuth {@code state} echoed back on the client redirect (for CSRF checks). */
    public String state() {
        return state;
    }

    /** Exchanges the authorization code at the token endpoint (authorization-code grant + PKCE). */
    public TokenResponse exchange() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", codeVerifier);
        if (clientSecret != null) {
            form.put("client_secret", clientSecret);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new FlowException("Token endpoint returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return TokenResponse.fromMap(JsonCodec.parseObject(response.body()));
        } catch (IOException e) {
            throw new FlowException("Token exchange failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("Token exchange interrupted", e);
        }
    }

    private static String formEncode(Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((key, value) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(key)).append('=').append(encode(value));
        });
        return body.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
