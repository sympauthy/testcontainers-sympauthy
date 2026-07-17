package com.sympauthy.testcontainers.flow;

import com.nimbusds.jwt.SignedJWT;
import com.sympauthy.testcontainers.AbstractSympauthyContainerIT;
import com.sympauthy.testcontainers.SympauthyContainer;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Drives a real sign-in end to end. Sign-in cannot be tested in isolation — the user has to exist
 * first — so this registers two flows on one {@link InteractiveFlowRegistry} (sharing a single client
 * and flow definition): a sign-up that creates the user, then a sign-in as that same user. The proof
 * that sign-in genuinely authenticated the account is that both runs yield id_tokens with the
 * <em>same</em> subject.
 */
class SignInWithInteractiveFlowIT extends AbstractSympauthyContainerIT {

    private static final String CLIENT_ID = "test-app";
    private static final String EMAIL = "ada@example.com";
    private static final String PASSWORD = "Str0ngP@ssw0rd!";

    /**
     * Password auth with an email identifier, plus the public client the test owns — wired to the
     * frontend's callback and flow id. Only the {@code flows.<id>} definition comes from {@code withFlows}.
     */
    private static Map<String, Object> config(InteractiveFlowRegistry registry) {
        return Map.of(
                "auth", Map.of(
                        "by-password", Map.of("enabled", true),
                        "identifier-claims", List.of("email")),
                "claims", Map.of("email", Map.of("enabled", true)),
                "clients", Map.of(registry.clientId(), Map.of(
                        "public", true,
                        "authorizationFlow", registry.flowId(),
                        "allowed-grant-types", List.of("authorization_code"),
                        "allowed-scopes", List.of("openid"),
                        "allowed-redirect-uris", List.of(registry.redirectUri()))));
    }

    @Test
    void signsInAsAPreviouslySignedUpUser() throws Exception {
        List<FlowStep.Type> steps = new ArrayList<>();
        try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(CLIENT_ID)
                        .withScopes("openid");
                SympauthyContainer sympauthy = new SympauthyContainer()
                        .withConfig(config(registry))
                        .withFlows(registry)) {
            InteractiveFlow signUp = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", EMAIL, "password", PASSWORD))
                    .withStepListener(step -> steps.add(step.type()));
            InteractiveFlow signIn = registry.newFlow()
                    .withSignInHandler(configuration -> Credentials.of(EMAIL, PASSWORD))
                    .withStepListener(step -> steps.add(step.type()));
            try {
                sympauthy.start();

                // 1. Sign up to create the user, and remember who was created.
                TokenResponse signUpTokens = signUp.run().exchange();
                String signUpSubject = subjectOf(signUpTokens);
                assertNotNull(signUpSubject, "sign-up id_token should identify a subject");

                // 2. Sign in as that user, in a fresh browser session (no lingering sign-up cookie).
                TokenResponse signInTokens = signIn.run().exchange();
                String signInSubject = subjectOf(signInTokens);

                assertEquals(List.of(
                                FlowStep.Type.SIGN_UP, FlowStep.Type.COMPLETED,
                                FlowStep.Type.SIGN_IN, FlowStep.Type.COMPLETED),
                        steps, "sign-up should run first, then the sign-in path");
                assertEquals(signUpSubject, signInSubject,
                        "sign-in should authenticate the user created at sign-up");
            } catch (Throwable failure) {
                System.out.println("=== SYMPAUTHY CONTAINER LOGS ===");
                System.out.println(safeLogs(sympauthy));
                throw failure;
            }
        }
    }

    private static String subjectOf(TokenResponse tokens) throws ParseException {
        assertNotNull(tokens.idToken(), "the openid scope should yield an id_token");
        return SignedJWT.parse(tokens.idToken()).getJWTClaimsSet().getSubject();
    }

    private static String safeLogs(SympauthyContainer sympauthy) {
        try {
            return sympauthy.getLogs();
        } catch (RuntimeException e) {
            return "(logs unavailable: " + e + ")";
        }
    }
}
