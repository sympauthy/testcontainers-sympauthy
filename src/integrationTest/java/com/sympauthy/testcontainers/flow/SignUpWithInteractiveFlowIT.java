package com.sympauthy.testcontainers.flow;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sympauthy.testcontainers.AbstractSympauthyContainerIT;
import com.sympauthy.testcontainers.SympauthyContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real interactive flow end to end: an {@link InteractiveFlow} mock frontend is wired into
 * the container with {@link SympauthyContainer#withFlow}, then SympAuthy redirects a browser through
 * the frontend's sign-up page to the client callback. The captured code is exchanged for tokens.
 */
class SignUpWithInteractiveFlowIT extends AbstractSympauthyContainerIT {

    private static final String CLIENT_ID = "test-app";

    /**
     * Password auth with an email identifier, plus the public client the test owns — wired to the
     * flow's callback and flow id. Only the {@code flows.<id>} definition comes from {@code withFlow}.
     */
    private static Map<String, Object> config(InteractiveFlow flow) {
        return Map.of(
                "auth", Map.of(
                        "by-password", Map.of("enabled", true),
                        "identifier-claims", List.of("email")),
                "claims", Map.of("email", Map.of("enabled", true)),
                "clients", Map.of(flow.clientId(), Map.of(
                        "public", true,
                        "authorizationFlow", flow.flowId(),
                        "allowed-grant-types", List.of("authorization_code"),
                        "allowed-scopes", List.of("openid"),
                        "allowed-redirect-uris", List.of(flow.redirectUri()))));
    }

    @Test
    void signsUpAndExchangesCodeForTokens() throws Exception {
        List<FlowStep.Type> steps = new ArrayList<>();
        try (InteractiveFlow flow = InteractiveFlow.forClient(CLIENT_ID)
                        .withScopes("openid")
                        .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "Str0ngP@ssw0rd!"))
                        .withStepListener(step -> steps.add(step.type()));
                SympauthyContainer sympauthy = new SympauthyContainer()
                        .withConfig(config(flow))
                        .withFlow(flow)) {
            try {
                sympauthy.start();

                AuthorizationResult result = flow.run();

                assertEquals(List.of(FlowStep.Type.SIGN_UP, FlowStep.Type.COMPLETED), steps);
                assertNotNull(result.code(), "should receive an authorization code");

                TokenResponse tokens = result.exchange();
                assertNotNull(tokens.accessToken(), "token response should carry an access token");
                assertNotNull(tokens.idToken(), "the openid scope should yield an id_token");

                JWTClaimsSet claims = SignedJWT.parse(tokens.idToken()).getJWTClaimsSet();
                assertEquals(sympauthy.getIssuerUrl(), claims.getIssuer(),
                        "id_token should be issued by this container");
                assertTrue(claims.getAudience().contains(CLIENT_ID),
                        "id_token audience should be the client, was: " + claims.getAudience());
                assertNotNull(claims.getSubject(), "id_token should identify a subject");
            } catch (Throwable failure) {
                System.out.println("=== SYMPAUTHY CONTAINER LOGS ===");
                System.out.println(safeLogs(sympauthy));
                throw failure;
            }
        }
    }

    private static String safeLogs(SympauthyContainer sympauthy) {
        try {
            return sympauthy.getLogs();
        } catch (RuntimeException e) {
            return "(logs unavailable: " + e + ")";
        }
    }
}