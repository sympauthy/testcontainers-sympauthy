package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.AbstractSympauthyContainerIT;
import com.sympauthy.testcontainers.Client;
import com.sympauthy.testcontainers.SympauthyContainer;
import com.sympauthy.testcontainers.client.TokenClient;
import com.sympauthy.testcontainers.internal.json.JsonCodec;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a <em>client-initiated</em> interactive flow end to end: a confidential client calls
 * {@code POST /api/v1/client/mfa/enrollment} to start a standalone MFA-enrollment flow for one of its
 * users, and the returned step link is driven through the new {@code CONFIRM} step. It proves both
 * outcomes — cancelling at the confirm step yields {@link FlowOutcome#CANCELED}, and approving it enrolls
 * TOTP and yields {@link FlowOutcome#SUCCESS} — and that enrollment genuinely took, by then requiring a
 * TOTP challenge when the user signs in again.
 */
class ClientInitiatedMfaEnrollmentIT extends AbstractSympauthyContainerIT {

    private static final String CLIENT_ID = "mfa-app";
    private static final String CLIENT_SECRET = "s3cr3t-mfa";
    private static final String EMAIL = "ada@example.com";
    private static final String PASSWORD = "Str0ngP@ssw0rd!";

    private static String returnUri(InteractiveFlowRegistry registry) {
        return registry.frontendUrl() + "/mfa-return";
    }

    private static String cancelUri(InteractiveFlowRegistry registry) {
        return registry.frontendUrl() + "/mfa-cancel";
    }

    /**
     * Password auth plus the confidential client the test owns. It needs the {@code client_credentials}
     * grant to call the enrollment API, and its redirect URIs must include the frontend callback (for the
     * sign-up) and the return/cancel URLs (for the enrollment). The client_credentials request for
     * {@code users:mfa:write} must be in {@code allowed-scopes} to pass the token endpoint's scope check,
     * and {@code features.grant-unhandled-scopes} then lets the grant actually hand it out (there is no
     * dedicated scope-granting rule).
     */
    private static Map<String, Object> config(InteractiveFlowRegistry registry) {
        return Map.of(
                "auth", Map.of(
                        "by-password", Map.of("enabled", true),
                        "identifier-claims", List.of("email")),
                "claims", Map.of("email", Map.of("enabled", true)),
                "features", Map.of("grant-unhandled-scopes", true),
                // A server-initiated (standalone) flow uses the default client template's authorization
                // flow, so point it at ours — otherwise it falls back to the built-in <root>/flow/* pages.
                "templates", Map.of("clients", Map.of("default",
                        Map.of("authorization-flow", registry.flowId()))),
                "clients", Map.of(registry.clientId(), Map.of(
                        "secret", registry.clientSecret(),
                        "authorizationFlow", registry.flowId(),
                        "allowed-grant-types", List.of("authorization_code", "client_credentials"),
                        "allowed-scopes", List.of("openid", "users:mfa:write"),
                        "default-scopes", List.of("openid"),
                        "allowed-redirect-uris", List.of(
                                registry.redirectUri(), returnUri(registry), cancelUri(registry)))));
    }

    @Test
    void clientInitiatedEnrollmentCanBeCancelledOrCompleted() throws Exception {
        try (InteractiveFlowRegistry registry = InteractiveFlowRegistry
                        .forClient(Client.confidentialClient(CLIENT_ID, CLIENT_SECRET))
                        .withScopes("openid")
                        .withMfaEnrollment();
                SympauthyContainer sympauthy = newContainer()
                        .withMfa()
                        .withConfig(config(registry))
                        .withFlows(registry)) {
            String returnUri = returnUri(registry);
            String cancelUri = cancelUri(registry);
            try {
                sympauthy.start();

                // 1. Sign up a user through the normal flow (MFA is optional, so it is skipped) to get an
                //    access token issued to this client — the "on behalf of" token the enrollment API needs.
                InteractiveFlow signUp = registry.newFlow()
                        .withSignUpHandler(resource -> Map.of("email", EMAIL, "password", PASSWORD));
                String userToken = signUp.run().exchange().accessToken();
                assertNotNull(userToken, "sign-up should yield an access token");

                // 2. Obtain a client-credentials token authorizing the MFA-write API.
                String tokenEndpoint = (String) JsonCodec.parseObject(fetchDiscovery(sympauthy)).get("token_endpoint");
                TokenClient tokenClient = new TokenClient(tokenEndpoint, HttpClient.newHttpClient(), registry.client());
                String clientToken = tokenClient.clientCredentials("users:mfa:write").accessToken();
                assertNotNull(clientToken, "client-credentials grant should yield a token");

                // 3a. Deny: initiate enrollment, then cancel at the confirm step.
                String denyLink = initiateEnrollment(sympauthy, clientToken, userToken, returnUri, cancelUri);
                FlowResult denied = registry.newFlow()
                        .withConfirmHandler(resource -> ConfirmDecision.CANCEL)
                        .driveFrom(denyLink, returnUri, cancelUri)
                        .drive();
                assertEquals(FlowOutcome.CANCELED, denied.outcome(),
                        "denying the confirm step should cancel the flow");
                assertEquals(List.of(FlowStep.Type.CONFIRM, FlowStep.Type.CANCEL), denied.stepTypes());

                // 3b. Approve: initiate again, confirm, and enroll TOTP.
                AtomicReference<String> secret = new AtomicReference<>();
                String approveLink = initiateEnrollment(sympauthy, clientToken, userToken, returnUri, cancelUri);
                FlowResult approved = registry.newFlow()
                        .withConfirmHandler(resource -> {
                            assertEquals("ENROLL_MFA", resource.action(), "confirm should describe the action");
                            assertEquals(CLIENT_ID, resource.initiatingClientId(),
                                    "confirm should name the initiating client");
                            return ConfirmDecision.CONFIRM;
                        })
                        .withTotpEnrollmentHandler(data -> {
                            secret.set(data.secret());
                            return Totp.code(data.secret());
                        })
                        .driveFrom(approveLink, returnUri, cancelUri)
                        .drive();
                assertEquals(FlowOutcome.SUCCESS, approved.outcome(), "approving should complete enrollment");
                assertTrue(approved.stepTypes().contains(FlowStep.Type.CONFIRM), approved.stepTypes().toString());
                assertTrue(approved.stepTypes().contains(FlowStep.Type.MFA), approved.stepTypes().toString());
                assertNotNull(secret.get(), "enrollment should expose a TOTP secret");

                // 4. Prove enrollment took effect: signing in now requires a TOTP challenge.
                InteractiveFlow signIn = registry.newFlow()
                        .withSignInHandler(resource -> Credentials.of(EMAIL, PASSWORD))
                        .withTotpChallengeHandler(() -> Totp.code(secret.get()));
                AuthorizationResult signInResult = signIn.run();
                assertNotNull(signInResult.code(), "sign-in should complete after answering the TOTP challenge");
                assertTrue(signIn.stepTypes().contains(FlowStep.Type.MFA),
                        "an enrolled user should face a TOTP challenge; path was: " + signIn.stepTypes());
            } catch (Throwable failure) {
                System.out.println("=== SYMPAUTHY CONTAINER LOGS ===");
                System.out.println(safeLogs(sympauthy));
                throw failure;
            }
        }
    }

    /** Calls the client-initiated MFA-enrollment API and returns the confirm-page link it hands back. */
    private static String initiateEnrollment(SympauthyContainer sympauthy, String clientToken, String userToken,
            String returnUri, String cancelUri) throws Exception {
        String body = "{\"access_token\":\"" + userToken + "\",\"return_uri\":\"" + returnUri
                + "\",\"cancel_uri\":\"" + cancelUri + "\"}";
        HttpResponse<String> response = apiPost(sympauthy, "/api/v1/client/mfa/enrollment", clientToken, body);
        assertEquals(200, response.statusCode(), "enrollment init should succeed, was: " + response.body());
        String redirectUrl = (String) JsonCodec.parseObject(response.body()).get("redirect_url");
        assertNotNull(redirectUrl, "enrollment init should return a redirect_url: " + response.body());
        return redirectUrl;
    }

    private static String safeLogs(SympauthyContainer sympauthy) {
        try {
            return sympauthy.getLogs();
        } catch (RuntimeException e) {
            return "(logs unavailable: " + e + ")";
        }
    }
}
