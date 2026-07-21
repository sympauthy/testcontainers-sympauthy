package com.sympauthy.testcontainers;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The credentials a caller authenticates as against SympAuthy's OAuth2 endpoints — a client id, an
 * optional secret, and (for a confidential client) which method transmits that secret. Mirrors
 * SympAuthy's own client model (id / secret / public), decoupled from any endpoint: it only knows how
 * to <em>authenticate a token request</em>. Reused by
 * {@link com.sympauthy.testcontainers.client.TokenClient} for both the authorization-code exchange and
 * the {@code client_credentials} grant.
 *
 * <ul>
 *   <li><b>Public</b> ({@link #publicClient(String)}): no secret; only the {@code client_id} is sent
 *       (the caller relies on PKCE).</li>
 *   <li><b>Confidential</b> ({@link #confidentialClient(String, String)}): the secret is sent either as
 *       a {@code client_secret} form parameter ({@link ClientAuthMethod#POST}, the default) or an
 *       {@code Authorization: Basic} header ({@link ClientAuthMethod#BASIC}). SympAuthy accepts both.</li>
 * </ul>
 */
public final class Client {

    /** How a confidential client transmits its secret to the token endpoint. */
    public enum ClientAuthMethod {
        /** HTTP Basic: {@code Authorization: Basic base64(client_id:client_secret)} ({@code client_secret_basic}). */
        BASIC,
        /** Form body: a {@code client_secret} parameter alongside {@code client_id} ({@code client_secret_post}). */
        POST
    }

    private final String id;
    private final String secret;
    private final ClientAuthMethod authMethod;

    private Client(String id, String secret, ClientAuthMethod authMethod) {
        this.id = id;
        this.secret = secret;
        this.authMethod = authMethod;
    }

    /** A public client (PKCE, no secret): only {@code client_id} is sent at the token endpoint. */
    public static Client publicClient(String id) {
        return new Client(id, null, ClientAuthMethod.POST);
    }

    /** A confidential client that sends its secret as a {@code client_secret} form parameter. */
    public static Client confidentialClient(String id, String secret) {
        return confidentialClient(id, secret, ClientAuthMethod.POST);
    }

    /** A confidential client that sends its secret via the given {@link ClientAuthMethod}. */
    public static Client confidentialClient(String id, String secret, ClientAuthMethod method) {
        if (secret == null) {
            throw new IllegalArgumentException("A confidential client requires a secret");
        }
        return new Client(id, secret, method);
    }

    /** The client id. */
    public String id() {
        return id;
    }

    /** The client secret, or {@code null} for a public client. */
    public String secret() {
        return secret;
    }

    /** Whether this is a public client (no secret). */
    public boolean isPublic() {
        return secret == null;
    }

    /**
     * Applies this client's authentication to a token request being built: always adds {@code client_id}
     * to the form, and — when confidential — either adds the {@code client_secret} form parameter
     * ({@link ClientAuthMethod#POST}) or sets an {@code Authorization: Basic} header
     * ({@link ClientAuthMethod#BASIC}) on the request.
     *
     * @param form    the form parameters being assembled for the token request (mutated in place)
     * @param request the request builder (mutated in place for Basic auth)
     */
    public void authenticate(Map<String, String> form, HttpRequest.Builder request) {
        form.put("client_id", id);
        if (secret == null) {
            return;
        }
        switch (authMethod) {
            case POST -> form.put("client_secret", secret);
            case BASIC -> request.header("Authorization", basicAuth(id, secret));
        }
    }

    private static String basicAuth(String id, String secret) {
        byte[] credentials = (id + ":" + secret).getBytes(StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(credentials);
    }
}
