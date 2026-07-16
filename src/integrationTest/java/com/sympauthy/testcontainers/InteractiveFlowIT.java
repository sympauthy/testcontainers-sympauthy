package com.sympauthy.testcontainers;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sympauthy.testcontainers.flow.AuthorizationResult;
import com.sympauthy.testcontainers.flow.FlowStep;
import com.sympauthy.testcontainers.flow.InteractiveFlow;
import com.sympauthy.testcontainers.flow.TokenResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real interactive flow end to end: boots SympAuthy with password sign-up + a public client
 * + a flow definition, signs a new user up through {@link InteractiveFlow}, and exchanges the
 * resulting authorization code for tokens.
 */
class InteractiveFlowIT extends AbstractSympauthyContainerIT {

    private static final String CLIENT_ID = "test-app";
    private static final String REDIRECT_URI = "http://localhost/callback";

    private static final Map<String, Object> CONFIG = Map.of(
            "auth", Map.of(
                    "by-password", Map.of("enabled", true),
                    "identifier-claims", List.of("email")),
            "claims", Map.of("email", Map.of("enabled", true)),
            "clients", Map.of(CLIENT_ID, Map.of(
                    "public", true,
                    "authorizationFlow", "default",
                    "allowed-grant-types", List.of("authorization_code"),
                    "allowed-scopes", List.of("openid"),
                    "allowed-redirect-uris", List.of(REDIRECT_URI))),
            "flows", Map.of("default", Map.of(
                    "type", "web",
                    "sign-in", "/sign-in",
                    "sign-up", "/sign-up",
                    "collect-claims", "/collect-claims",
                    "validate-claims", "/validate-claims",
                    "error", "/error")));

    @Test
    void signsUpAndExchangesCodeForTokens() throws Exception {
        SympauthyContainer sympauthy = new SympauthyContainer().withConfig(CONFIG);
        try {
            sympauthy.start();

            List<FlowStep.Type> steps = new ArrayList<>();
            AuthorizationResult result = InteractiveFlow.against(sympauthy)
                    .withClientId(CLIENT_ID)
                    .withRedirectUri(REDIRECT_URI)
                    .withScopes("openid")
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "Str0ngP@ssw0rd!"))
                    .withStepListener(step -> steps.add(step.type()))
                    .run();

            // The per-step callback observed the whole flow, ending at the client redirect.
            assertEquals(
                    List.of(FlowStep.Type.CONFIGURATION, FlowStep.Type.SIGN_UP, FlowStep.Type.COMPLETED),
                    steps);
            assertNotNull(result.code(), "should receive an authorization code");

            // The code exchanges into real tokens issued by this very server.
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
        } finally {
            sympauthy.stop();
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
