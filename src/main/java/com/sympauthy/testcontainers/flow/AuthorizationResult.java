package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.client.TokenClient;
import com.sympauthy.testcontainers.client.TokenResponse;

/**
 * The outcome of driving the flow to the client redirect: the authorization {@link #code()} (and the
 * OAuth {@code state} echoed back), plus {@link #exchange()} to trade the code for tokens at the
 * token endpoint using the authorization-code grant and the PKCE verifier from this run. The exchange
 * (including client authentication) is delegated to the {@link TokenClient} the registry built for this
 * run.
 */
public final class AuthorizationResult {

    private final String code;
    private final String state;
    private final String redirectUri;
    private final String codeVerifier;
    private final TokenClient tokenClient;

    AuthorizationResult(String code, String state, String redirectUri, String codeVerifier,
            TokenClient tokenClient) {
        this.code = code;
        this.state = state;
        this.redirectUri = redirectUri;
        this.codeVerifier = codeVerifier;
        this.tokenClient = tokenClient;
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
        return tokenClient.exchangeAuthorizationCode(code, redirectUri, codeVerifier);
    }
}
