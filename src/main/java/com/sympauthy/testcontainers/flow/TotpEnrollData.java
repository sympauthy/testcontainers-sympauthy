package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/mfa/totp/enroll} response: the shared secret a user must register in
 * their authenticator to enroll TOTP. {@link #secret()} is the Base32-encoded secret (feed it to
 * {@link Totp#code(String)} to produce a valid code); {@link #uri()} is the equivalent
 * {@code otpauth://} URI a real frontend would render as a QR code.
 */
public final class TotpEnrollData {

    private final String uri;
    private final String secret;
    private final ClientReference client;
    private final Map<String, Object> raw;

    private TotpEnrollData(String uri, String secret, ClientReference client, Map<String, Object> raw) {
        this.uri = uri;
        this.secret = secret;
        this.client = client;
        this.raw = raw;
    }

    static TotpEnrollData fromMap(Map<String, Object> map) {
        return new TotpEnrollData(str(map.get("uri")), str(map.get("secret")), ClientReference.fromBody(map), map);
    }

    /** The {@code otpauth://totp/...} provisioning URI. */
    public String uri() {
        return uri;
    }

    /** The Base32-encoded TOTP shared secret. */
    public String secret() {
        return secret;
    }

    /** The client the session was started for, or {@code null} when an administrator started it. */
    public ClientReference client() {
        return client;
    }

    /** The full parsed response body. */
    public Map<String, Object> raw() {
        return raw;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
