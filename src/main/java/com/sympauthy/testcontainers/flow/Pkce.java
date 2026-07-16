package com.sympauthy.testcontainers.flow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A PKCE (RFC 7636) verifier/challenge pair using the {@code S256} method — the only method
 * SympAuthy accepts. No external dependency: {@link SecureRandom} + SHA-256 + URL-safe Base64.
 */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    final String codeVerifier;
    final String codeChallenge;
    final String method = "S256";

    private Pkce(String codeVerifier, String codeChallenge) {
        this.codeVerifier = codeVerifier;
        this.codeChallenge = codeChallenge;
    }

    static Pkce generate() {
        byte[] verifierBytes = new byte[32];
        RANDOM.nextBytes(verifierBytes);
        String verifier = URL_ENCODER.encodeToString(verifierBytes);
        String challenge = URL_ENCODER.encodeToString(sha256(verifier));
        return new Pkce(verifier, challenge);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for PKCE but unavailable", e);
        }
    }
}
