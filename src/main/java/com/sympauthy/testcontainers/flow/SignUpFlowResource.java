package com.sympauthy.testcontainers.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/sign-up} response: what the server asks for on the sign-up step. Passed
 * to a {@link SignUpHandler} so a test can build the field map from what the server advertises (the
 * password identifier claims to collect, whether a sign-in cross-link exists). {@link #raw()} exposes
 * the full body for anything not surfaced here.
 *
 * <p>The password identifier claims are the fields sign-up collects: they uniquely identify the user and
 * are the only claims saved on sign-up — any other submitted claim is discarded. Each carries its full
 * metadata ({@code id}, {@code required}, {@code name}, {@code type}, {@code group}) as a {@link Claim}.
 *
 * <p>The server omits null fields entirely, so {@link #passwordEnabled()} reflects whether the
 * {@code password} object was present and {@link #signInRedirectUrl()} is {@code null} when the flow
 * offers no sign-in cross-link (e.g. during an invitation flow).
 */
public final class SignUpFlowResource {

    private final boolean passwordEnabled;
    private final List<Claim> passwordIdentifierClaims;
    private final String signInRedirectUrl;
    private final Map<String, Object> raw;

    private SignUpFlowResource(
            boolean passwordEnabled,
            List<Claim> passwordIdentifierClaims,
            String signInRedirectUrl,
            Map<String, Object> raw) {
        this.passwordEnabled = passwordEnabled;
        this.passwordIdentifierClaims = List.copyOf(passwordIdentifierClaims);
        this.signInRedirectUrl = signInRedirectUrl;
        this.raw = raw;
    }

    @SuppressWarnings("unchecked")
    static SignUpFlowResource fromMap(Map<String, Object> map) {
        Map<String, Object> password = asMap(map.get("password"));
        boolean passwordEnabled = map.get("password") instanceof Map<?, ?>;

        List<Claim> identifierClaims = new ArrayList<>();
        if (password.get("identifier_claims") instanceof List<?> ids) {
            for (Object element : ids) {
                if (element instanceof Map<?, ?> claimMap) {
                    identifierClaims.add(Claim.fromMap((Map<String, Object>) claimMap));
                }
            }
        }

        return new SignUpFlowResource(
                passwordEnabled,
                identifierClaims,
                str(map.get("sign_in_redirect_url")),
                map);
    }

    /** Whether the sign-up step accepts a password (the {@code password} object was present). */
    public boolean passwordEnabled() {
        return passwordEnabled;
    }

    /**
     * The claims that identify a user for password sign-up (e.g. {@code email}) — also the claims sign-up
     * collects and saves, each with its full metadata.
     */
    public List<Claim> passwordIdentifierClaims() {
        return passwordIdentifierClaims;
    }

    /** The URL of the sign-in page the flow cross-links to, or {@code null} when sign-in is not offered. */
    public String signInRedirectUrl() {
        return signInRedirectUrl;
    }

    /** The full parsed response body. */
    public Map<String, Object> raw() {
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
