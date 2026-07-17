package com.sympauthy.testcontainers.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code GET /api/v1/flow/configuration} response: the features, claims and providers the
 * flow advertises. Passed to sign-in/sign-up handlers so a test can branch on what the server
 * offers. {@link #raw()} exposes the full body for anything not surfaced here.
 */
public final class FlowConfiguration {

    /** A third-party login provider advertised by the flow. */
    public record Provider(String id, String name, String authorizeUrl) {
    }

    private final boolean passwordSignIn;
    private final boolean signUpEnabled;
    private final boolean invitationEnabled;
    private final List<Claim> claims;
    private final List<String> passwordIdentifierClaims;
    private final List<Provider> providers;
    private final Map<String, Object> raw;

    private FlowConfiguration(
            boolean passwordSignIn,
            boolean signUpEnabled,
            boolean invitationEnabled,
            List<Claim> claims,
            List<String> passwordIdentifierClaims,
            List<Provider> providers,
            Map<String, Object> raw) {
        this.passwordSignIn = passwordSignIn;
        this.signUpEnabled = signUpEnabled;
        this.invitationEnabled = invitationEnabled;
        this.claims = List.copyOf(claims);
        this.passwordIdentifierClaims = List.copyOf(passwordIdentifierClaims);
        this.providers = List.copyOf(providers);
        this.raw = raw;
    }

    @SuppressWarnings("unchecked")
    static FlowConfiguration fromMap(Map<String, Object> map) {
        Map<String, Object> features = asMap(map.get("features"));

        List<Claim> claims = new ArrayList<>();
        if (map.get("claims") instanceof List<?> claimList) {
            for (Object element : claimList) {
                if (element instanceof Map<?, ?> claimMap) {
                    claims.add(Claim.fromMap((Map<String, Object>) claimMap));
                }
            }
        }

        List<String> identifierClaims = new ArrayList<>();
        Map<String, Object> password = asMap(map.get("password"));
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

        return new FlowConfiguration(
                Boolean.TRUE.equals(features.get("password_sign_in")),
                Boolean.TRUE.equals(features.get("sign_up_enabled")),
                Boolean.TRUE.equals(features.get("invitation_enabled")),
                claims,
                identifierClaims,
                providers,
                map);
    }

    public boolean passwordSignIn() {
        return passwordSignIn;
    }

    public boolean signUpEnabled() {
        return signUpEnabled;
    }

    public boolean invitationEnabled() {
        return invitationEnabled;
    }

    public List<Claim> claims() {
        return claims;
    }

    public List<String> passwordIdentifierClaims() {
        return passwordIdentifierClaims;
    }

    public List<Provider> providers() {
        return providers;
    }

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
