package com.sympauthy.testcontainers.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/sign-up} response: what the server asks for on the sign-up step. Passed
 * to a {@link SignUpHandler} so a test can build the field map from what the server advertises (password
 * identifiers, claimable fields, whether a sign-in cross-link exists). {@link #raw()} exposes the full
 * body for anything not surfaced here.
 *
 * <p>The server omits null fields entirely, so {@link #passwordEnabled()} reflects whether the
 * {@code password} object was present and {@link #signInRedirectUrl()} is {@code null} when the flow
 * offers no sign-in cross-link (e.g. during an invitation flow).
 */
public final class SignUpFlowResource {

    private final boolean passwordEnabled;
    private final List<String> passwordIdentifierClaims;
    private final List<Claim> claims;
    private final String signInRedirectUrl;
    private final Map<String, Object> raw;

    private SignUpFlowResource(
            boolean passwordEnabled,
            List<String> passwordIdentifierClaims,
            List<Claim> claims,
            String signInRedirectUrl,
            Map<String, Object> raw) {
        this.passwordEnabled = passwordEnabled;
        this.passwordIdentifierClaims = List.copyOf(passwordIdentifierClaims);
        this.claims = List.copyOf(claims);
        this.signInRedirectUrl = signInRedirectUrl;
        this.raw = raw;
    }

    @SuppressWarnings("unchecked")
    static SignUpFlowResource fromMap(Map<String, Object> map) {
        Map<String, Object> password = asMap(map.get("password"));
        boolean passwordEnabled = map.get("password") instanceof Map<?, ?>;

        List<String> identifierClaims = new ArrayList<>();
        if (password.get("identifier_claims") instanceof List<?> ids) {
            for (Object id : ids) {
                identifierClaims.add(String.valueOf(id));
            }
        }

        List<Claim> claims = new ArrayList<>();
        if (map.get("claims") instanceof List<?> claimList) {
            for (Object element : claimList) {
                if (element instanceof Map<?, ?> claimMap) {
                    claims.add(Claim.fromMap((Map<String, Object>) claimMap));
                }
            }
        }

        return new SignUpFlowResource(
                passwordEnabled,
                identifierClaims,
                claims,
                str(map.get("sign_in_redirect_url")),
                map);
    }

    /** Whether the sign-up step accepts a password (the {@code password} object was present). */
    public boolean passwordEnabled() {
        return passwordEnabled;
    }

    /** The claims that identify a user for password sign-up (e.g. {@code email}). */
    public List<String> passwordIdentifierClaims() {
        return passwordIdentifierClaims;
    }

    /** The claimable fields the sign-up step asks the user to provide. */
    public List<Claim> claims() {
        return claims;
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
