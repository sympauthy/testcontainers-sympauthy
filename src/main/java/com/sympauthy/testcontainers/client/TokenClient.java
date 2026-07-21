package com.sympauthy.testcontainers.client;

import com.sympauthy.testcontainers.Client;
import com.sympauthy.testcontainers.internal.json.JsonCodec;

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
 * A thin client over SympAuthy's OAuth2 <b>token endpoint</b>, authenticating as a given {@link Client}
 * (public or confidential). It owns the {@code application/x-www-form-urlencoded} POST and the JSON
 * response parsing, and delegates client authentication to {@link Client#authenticate}.
 *
 * <p>Two grants are supported:
 * <ul>
 *   <li>{@link #exchangeAuthorizationCode} — trade an authorization code for tokens (authorization-code
 *       grant + PKCE), used at the end of the interactive flow;</li>
 *   <li>{@link #clientCredentials} — obtain a token directly as a confidential client, e.g. to call
 *       SympAuthy's Client API, with no interactive flow involved.</li>
 * </ul>
 */
public final class TokenClient {

    private final String tokenEndpoint;
    private final HttpClient httpClient;
    private final Client client;

    /**
     * @param tokenEndpoint the OAuth2 token endpoint URL (from the discovery document)
     * @param httpClient    the HTTP client to use
     * @param client        the client to authenticate as
     */
    public TokenClient(String tokenEndpoint, HttpClient httpClient, Client client) {
        this.tokenEndpoint = tokenEndpoint;
        this.httpClient = httpClient;
        this.client = client;
    }

    /**
     * Exchanges an authorization code for tokens: the authorization-code grant with the PKCE
     * {@code code_verifier}, plus client authentication (a secret when confidential).
     */
    public TokenResponse exchangeAuthorizationCode(String code, String redirectUri, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        return post(form);
    }

    /**
     * Obtains a token via the {@code client_credentials} grant (a confidential client authenticating
     * itself), optionally scoping the request.
     */
    public TokenResponse clientCredentials(String... scopes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (scopes != null && scopes.length > 0) {
            form.put("scope", String.join(" ", scopes));
        }
        return post(form);
    }

    private TokenResponse post(Map<String, String> form) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        client.authenticate(form, builder);
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new SympauthyApiException("Token endpoint returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return TokenResponse.fromMap(JsonCodec.parseObject(response.body()));
        } catch (IOException e) {
            throw new SympauthyApiException("Token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SympauthyApiException("Token request interrupted", e);
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
