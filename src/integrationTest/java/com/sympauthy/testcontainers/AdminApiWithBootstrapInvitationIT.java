package com.sympauthy.testcontainers;

import com.sympauthy.testcontainers.client.TokenResponse;
import com.sympauthy.testcontainers.flow.AuthorizationResult;
import com.sympauthy.testcontainers.flow.InteractiveFlow;
import com.sympauthy.testcontainers.flow.InteractiveFlowRegistry;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the admin bootstrap-invitation support creates a working admin user. It enables
 * the {@code admin} environment (which ships the admin API, the {@code admin} audience, the
 * {@code is_sympauthy_admin} claim, a scope-granting rule and a {@code first-admin} bootstrap
 * invitation), reads that invitation's token from the startup logs, redeems it through the interactive
 * flow to sign up the first admin, and then calls the Admin API with the resulting access token —
 * asserting it succeeds with the token and is rejected without it.
 */
class AdminApiWithBootstrapInvitationIT extends AbstractSympauthyContainerIT {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Str0ngP@ssw0rd!";

    /** Password auth with an email identifier, so the invited admin can sign up with email/password. */
    private static Map<String, Object> passwordAuthConfig() {
        return Map.of(
                "auth", Map.of(
                        "by-password", Map.of("enabled", true),
                        "identifier-claims", List.of("email")),
                "claims", Map.of("email", Map.of("enabled", true)));
    }

    @Test
    void redeemsTheBootstrapInvitationAndCallsTheAdminApi() throws Exception {
        try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("admin-app")
                        .withFlowId("admin-flow");
                SympauthyContainer sympauthy = new SympauthyContainer()
                        .withAdmin()
                        .withAdminClient(registry, "admin:users:read")
                        .withConfig(passwordAuthConfig())
                        .withFlows(registry)) {
            try {
                sympauthy.start();

                // The admin environment's built-in bootstrap invitation, read from the startup logs.
                String token = sympauthy.getBootstrapInvitationToken("first-admin");
                assertNotNull(token, "should log the first-admin bootstrap invitation token");

                // Redeem it: signing up with the invitation token creates the first admin user.
                InteractiveFlow flow = registry.newFlow()
                        .withInvitationToken(token)
                        .withSignUpHandler(configuration ->
                                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
                AuthorizationResult result = flow.run();
                assertNotNull(result.code(), "invitation sign-up should yield an authorization code");

                TokenResponse tokens = result.exchange();
                String accessToken = tokens.accessToken();
                assertNotNull(accessToken, "token exchange should return an access token");

                // The access token carries the admin scope (granted from is_sympauthy_admin), so the
                // Admin API accepts it...
                HttpResponse<String> authorized = apiGet(sympauthy, "/api/v1/admin/users", accessToken);
                assertEquals(200, authorized.statusCode(),
                        "admin token should be accepted by the admin API, was: " + authorized.body());

                // ...and rejects the same call without a token, proving the scope is genuinely enforced.
                HttpResponse<String> anonymous = apiGet(sympauthy, "/api/v1/admin/users", null);
                assertTrue(anonymous.statusCode() == 401 || anonymous.statusCode() == 403,
                        "admin API should reject an unauthenticated call, was: " + anonymous.statusCode());
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
