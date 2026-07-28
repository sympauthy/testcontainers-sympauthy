package com.sympauthy.testcontainers.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/sign-in} response: what the server offers on the sign-in step. Passed
 * to a {@link SignInHandler} so a test can branch on what the server advertises (password identifiers,
 * third-party providers, whether a sign-up cross-link exists). {@link #raw()} exposes the full body for
 * anything not surfaced here.
 *
 * <p>The server omits null fields entirely, so {@link #passwordEnabled()} reflects whether the
 * {@code password} object was present and {@link #signUpRedirectUrl()} is {@code null} when the flow
 * offers no sign-up cross-link.
 */
public final class SignInFlowResource {

    /** A third-party login provider advertised by the sign-in step. */
    public record Provider(String id, String name, String authorizeUrl) {
    }

    private final boolean passwordEnabled;
    private final List<String> passwordIdentifierClaims;
    private final List<Provider> providers;
    private final String signUpRedirectUrl;
    private final Map<String, Object> raw;

    private SignInFlowResource(
            boolean passwordEnabled,
            List<String> passwordIdentifierClaims,
            List<Provider> providers,
            String signUpRedirectUrl,
            Map<String, Object> raw) {
        this.passwordEnabled = passwordEnabled;
        this.passwordIdentifierClaims = List.copyOf(passwordIdentifierClaims);
        this.providers = List.copyOf(providers);
        this.signUpRedirectUrl = signUpRedirectUrl;
        this.raw = raw;
    }

    static SignInFlowResource fromMap(Map<String, Object> map) {
        Map<String, Object> password = asMap(map.get("password"));
        boolean passwordEnabled = map.get("password") instanceof Map<?, ?>;

        List<String> identifierClaims = new ArrayList<>();
        if (password.get("identifier_claims") instanceof List<?> ids) {
            for (Object id : ids) {
                identifierClaims.add(String.valueOf(id));
            }
        }

        List<Provider> providers = new ArrayList<>();
        if (map.get("providers") instanceof List<?> providerList) {
            for (Object element : providerList) {
                if (element instanceof Map<?, ?> providerMap) {
                    providers.add(new Provider(
                            str(providerMap.get("id")),
                            str(providerMap.get("name")),
                            str(providerMap.get("authorize_url"))));
                }
            }
        }

        return new SignInFlowResource(
                passwordEnabled,
                identifierClaims,
                providers,
                str(map.get("sign_up_redirect_url")),
                map);
    }

    /** Whether the sign-in step accepts a password (the {@code password} object was present). */
    public boolean passwordEnabled() {
        return passwordEnabled;
    }

    /** The claims that identify a user for password sign-in (e.g. {@code email}). */
    public List<String> passwordIdentifierClaims() {
        return passwordIdentifierClaims;
    }

    /** The third-party providers the sign-in step advertises. */
    public List<Provider> providers() {
        return providers;
    }

    /** The URL of the sign-up page the flow cross-links to, or {@code null} when sign-up is not offered. */
    public String signUpRedirectUrl() {
        return signUpRedirectUrl;
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
