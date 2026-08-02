package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.Client;
import com.sympauthy.testcontainers.client.TokenResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the mock flow frontend against a stub SympAuthy (the in-JVM {@link TestFlowServer}) — no
 * Docker. The stub plays SympAuthy's role: its {@code /authorize} redirects the browser to the
 * frontend's page URLs, and its Flow API returns the {@code redirect_url}s that move the browser from
 * page to page. This exercises the real mechanism: SympAuthy orchestrates, the frontend renders pages.
 */
class InteractiveFlowTest {

    private static final String FLOW_STATE = "flow-state-jwt";
    private static final String CODE = "auth-code-123";
    /** RFC 6238 Appendix B ASCII seed, Base32-encoded — a stub TOTP enrollment secret. */
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    /** A password {@code identifier_claims} entry in the post-#283 full-object shape. */
    private static final String IDENTIFIER_CLAIM =
            "{\"id\":\"email\",\"required\":true,\"name\":\"Email\",\"type\":\"string\",\"group\":null}";

    @Test
    void drivesSignUpToACodeAndTokens() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            AuthorizationResult result = flow.run();

            assertEquals(CODE, result.code());
            assertEquals(List.of(FlowStep.Type.SIGN_UP, FlowStep.Type.COMPLETED), flow.stepTypes());

            // State transport across the mock frontend's server-side Flow API calls.
            assertEquals(FLOW_STATE, sympauthy.firstRequest("GET", "/api/v1/flow/sign-up").stateQueryParam());
            assertEquals("State " + FLOW_STATE,
                    sympauthy.firstRequest("POST", "/api/v1/flow/sign-up").headers().get("Authorization"));

            TokenResponse tokens = result.exchange();
            assertEquals("at", tokens.accessToken());
            String tokenBody = sympauthy.firstRequest("POST", "/api/oauth2/token").body();
            assertTrue(tokenBody.contains("grant_type=authorization_code"));
            assertTrue(tokenBody.contains("code=" + CODE));
            assertTrue(tokenBody.contains("code_verifier="));
            // A public client sends no secret — PKCE only.
            assertFalse(tokenBody.contains("client_secret="), tokenBody);
        }
    }

    @Test
    void sendsTheClientSecretAtExchangeForAConfidentialClient() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry
                        .forClient(Client.confidentialClient("test-app", "s3cr3t")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            flow.run().exchange();

            // A confidential client sends its secret as a client_secret_post form parameter by default.
            String tokenBody = sympauthy.firstRequest("POST", "/api/oauth2/token").body();
            assertTrue(tokenBody.contains("client_secret=s3cr3t"), tokenBody);
        }
    }

    @Test
    void sendsTheClientSecretViaBasicAuthWhenConfigured() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry
                        .forClient(Client.confidentialClient("test-app", "s3cr3t", Client.ClientAuthMethod.BASIC))
                        .withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            flow.run().exchange();

            // Basic auth: the secret rides the Authorization header, not the form body.
            TestFlowServer.RecordedRequest token = sympauthy.firstRequest("POST", "/api/oauth2/token");
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("test-app:s3cr3t".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, token.headers().get("Authorization"));
            assertFalse(token.body().contains("client_secret="), token.body());
        }
    }

    @Test
    void drivesCollectClaimsPage() {
        // Also exercises StepListener: it observes every step as it happens (with its data) and stays
        // in sync with the flow's own stepTypes() history.
        List<FlowStep> observed = new ArrayList<>();
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"))
                    .withClaimsHandler(claims -> Map.of("given_name", "Ada"))
                    .withStepListener(observed::add);

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/collect-claims?state=" + FLOW_STATE)));
            sympauthy.route("GET", "/api/v1/flow/claims", request -> TestFlowServer.Response.json(200,
                    "{\"claims\":[{\"id\":\"given_name\",\"required\":true,\"name\":\"Given name\",\"type\":\"string\"}]}"));
            sympauthy.route("POST", "/api/v1/flow/claims", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            AuthorizationResult result = flow.run();

            assertEquals(CODE, result.code());
            assertEquals(List.of(FlowStep.Type.SIGN_UP, FlowStep.Type.CLAIMS, FlowStep.Type.COMPLETED), flow.stepTypes());
            // The listener sees the same sequence and can read each step's raw data.
            assertEquals(flow.stepTypes(), observed.stream().map(FlowStep::type).toList());
            assertTrue(observed.stream().anyMatch(step ->
                    step.type() == FlowStep.Type.CLAIMS && step.data().containsKey("claims")));
            assertTrue(sympauthy.firstRequest("POST", "/api/v1/flow/claims").body().contains("given_name"));
        }
    }

    @Test
    void sendsTheInvitationTokenOnAuthorize() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("admin-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withInvitationToken("boot-tok-123")
                    .withSignUpHandler(configuration -> Map.of("email", "admin@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            AuthorizationResult result = flow.run();

            assertEquals(CODE, result.code());
            // The invitation token redeems the invitation by riding the authorize request as a query param.
            TestFlowServer.RecordedRequest authorize = sympauthy.firstRequest("GET", "/api/oauth2/authorize");
            assertEquals("boot-tok-123",
                    TestFlowServer.RecordedRequest.queryParam(authorize.query(), "invitation_token"));
        }
    }

    @Test
    void sendsTheNonceOnAuthorize() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withNonce("n-0S6_WzA2Mj")
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/callback?state=oauth&code=" + CODE)));
            attach(registry, sympauthy);

            AuthorizationResult result = flow.run();

            assertEquals(CODE, result.code());
            // The nonce rides the authorize request as a query param, to be echoed in the id_token.
            TestFlowServer.RecordedRequest authorize = sympauthy.firstRequest("GET", "/api/oauth2/authorize");
            assertEquals("n-0S6_WzA2Mj",
                    TestFlowServer.RecordedRequest.queryParam(authorize.query(), "nonce"));
        }
    }

    @Test
    void abortsWhenSympAuthyRedirectsToTheErrorPage() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow()
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            registerSympAuthy(sympauthy, registry);
            sympauthy.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo(registry.frontendUrl() + "/error?error=nope")));
            attach(registry, sympauthy);

            assertThrows(FlowException.class, flow::run);
        }
    }

    @Test
    void failsWhenSympAuthyRedirectsToAnUnsupportedPage() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow();

            registerSympAuthy(sympauthy, registry);
            // The authorization server sends the browser to a flow page the mock frontend does not serve.
            sympauthy.route("GET", "/api/oauth2/authorize", request ->
                    TestFlowServer.Response.seeOther(registry.frontendUrl() + "/mfa?state=" + FLOW_STATE));
            attach(registry, sympauthy);

            UnsupportedFlowStepException failure = assertThrows(UnsupportedFlowStepException.class, flow::run);
            assertTrue(failure.getMessage().contains("/mfa"), failure.getMessage());
        }
    }

    @Test
    void failsWhenNoAuthenticationHandlerIsConfigured() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow();

            registerSympAuthy(sympauthy, registry);
            attach(registry, sympauthy);

            assertThrows(FlowException.class, flow::run);
        }
    }

    @Test
    void failsWhenNotAttachedToAContainer() {
        try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app")).withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow().withSignUpHandler(configuration -> Map.of());
            assertThrows(IllegalStateException.class, flow::run);
        }
    }

    @Test
    void drivesConfirmApproveThroughTotpEnrollmentToSuccess() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app"))) {
            String successUrl = registry.frontendUrl() + "/mfa-return";
            String cancelUrl = registry.frontendUrl() + "/mfa-cancel";
            String startUrl = registry.frontendUrl() + "/confirm?state=" + FLOW_STATE;

            AtomicReference<ConfirmFlowResource> seen = new AtomicReference<>();
            AtomicReference<String> submittedCode = new AtomicReference<>();
            InteractiveFlow flow = registry.newFlow()
                    .withConfirmHandler(resource -> {
                        seen.set(resource);
                        return ConfirmDecision.CONFIRM;
                    })
                    .withTotpEnrollmentHandler(data -> {
                        String code = Totp.code(data.secret());
                        submittedCode.set(code);
                        return code;
                    })
                    .driveFrom(startUrl, successUrl, cancelUrl);

            // The server-orchestrated enrollment: confirm → mfa selection (auto) → totp enroll → return.
            sympauthy.route("GET", "/api/v1/flow/confirm", request -> TestFlowServer.Response.json(200,
                    "{\"action\":\"ENROLL_MFA\",\"initiating_client_id\":\"test-app\"}"));
            sympauthy.route("POST", "/api/v1/flow/confirm", request -> TestFlowServer.Response.json(200,
                    redirectTo(registry.frontendUrl() + "/mfa-selection-for-enrollment?state=" + FLOW_STATE)));
            sympauthy.route("GET", "/api/v1/flow/mfa/enrollment", request -> TestFlowServer.Response.json(200,
                    redirectTo(registry.frontendUrl() + "/mfa-totp-enroll?state=" + FLOW_STATE)));
            sympauthy.route("GET", "/api/v1/flow/mfa/totp/enroll", request -> TestFlowServer.Response.json(200,
                    "{\"uri\":\"otpauth://totp/x?secret=" + SECRET + "\",\"secret\":\"" + SECRET + "\"}"));
            sympauthy.route("POST", "/api/v1/flow/mfa/totp/enroll", request ->
                    TestFlowServer.Response.json(200, redirectTo(successUrl)));
            attach(registry, sympauthy);

            FlowResult result = flow.drive();

            assertTrue(result.isSuccess());
            assertEquals(FlowOutcome.SUCCESS, result.outcome());
            assertEquals(List.of(FlowStep.Type.CONFIRM, FlowStep.Type.MFA), flow.stepTypes());
            assertEquals("ENROLL_MFA", seen.get().action());
            assertEquals("test-app", seen.get().initiatingClientId());
            // State transport: GET carries ?state=, the bodyless confirm POST carries Authorization: State.
            assertEquals(FLOW_STATE, sympauthy.firstRequest("GET", "/api/v1/flow/confirm").stateQueryParam());
            TestFlowServer.RecordedRequest confirmPost = sympauthy.firstRequest("POST", "/api/v1/flow/confirm");
            assertEquals("State " + FLOW_STATE, confirmPost.headers().get("Authorization"));
            assertEquals("", confirmPost.body());
            // The code generated from the enrollment secret was submitted to the enroll endpoint.
            assertTrue(sympauthy.firstRequest("POST", "/api/v1/flow/mfa/totp/enroll").body()
                    .contains("\"code\":\"" + submittedCode.get() + "\""), submittedCode.get());
        }
    }

    @Test
    void drivesConfirmDenyToCancel() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app"))) {
            String successUrl = registry.frontendUrl() + "/mfa-return";
            String cancelUrl = registry.frontendUrl() + "/mfa-cancel";
            String startUrl = registry.frontendUrl() + "/confirm?state=" + FLOW_STATE;

            InteractiveFlow flow = registry.newFlow()
                    .withConfirmHandler(resource -> ConfirmDecision.CANCEL)
                    .driveFrom(startUrl, successUrl, cancelUrl);

            sympauthy.route("GET", "/api/v1/flow/confirm", request -> TestFlowServer.Response.json(200,
                    "{\"action\":\"ENROLL_MFA\"}"));
            sympauthy.route("POST", "/api/v1/flow/cancel", request -> TestFlowServer.Response.json(200,
                    redirectTo(cancelUrl + "?error=access_denied")));
            attach(registry, sympauthy);

            FlowResult result = flow.drive();

            assertTrue(result.isCanceled());
            assertEquals(FlowOutcome.CANCELED, result.outcome());
            assertEquals(List.of(FlowStep.Type.CONFIRM, FlowStep.Type.CANCEL), flow.stepTypes());
            assertEquals("access_denied", result.terminalParam("error"));
            // The cancel POST is bodyless and carries the state in the Authorization header.
            TestFlowServer.RecordedRequest cancelPost = sympauthy.firstRequest("POST", "/api/v1/flow/cancel");
            assertEquals("State " + FLOW_STATE, cancelPost.headers().get("Authorization"));
            assertEquals("", cancelPost.body());
        }
    }

    @Test
    void rejectsDriveUrlsOutsideTheFrontend() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app"))) {
            InteractiveFlow flow = registry.newFlow()
                    .withConfirmHandler(resource -> ConfirmDecision.CONFIRM)
                    .driveFrom(registry.frontendUrl() + "/confirm?state=x",
                            "https://evil.example.com/return", registry.frontendUrl() + "/mfa-cancel");
            attach(registry, sympauthy);

            assertThrows(IllegalArgumentException.class, flow::drive);
        }
    }

    private static void registerSympAuthy(TestFlowServer sympauthy, InteractiveFlowRegistry registry) {
        sympauthy.route("GET", "/.well-known/openid-configuration", request ->
                TestFlowServer.Response.json(200, discovery(sympauthy.baseUrl())));
        // /authorize sends the browser to the mock frontend's sign-in page with a state token.
        sympauthy.route("GET", "/api/oauth2/authorize", request ->
                TestFlowServer.Response.seeOther(registry.frontendUrl() + "/sign-in?state=" + FLOW_STATE));
        sympauthy.route("GET", "/api/v1/flow/sign-in", request -> TestFlowServer.Response.json(200,
                "{\"password\":{\"identifier_claims\":[" + IDENTIFIER_CLAIM + "]},\"providers\":[]}"));
        sympauthy.route("GET", "/api/v1/flow/sign-up", request -> TestFlowServer.Response.json(200,
                "{\"password\":{\"identifier_claims\":[" + IDENTIFIER_CLAIM + "]}}"));
        sympauthy.route("POST", "/api/oauth2/token", request -> TestFlowServer.Response.json(200,
                "{\"access_token\":\"at\",\"id_token\":\"it\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
    }

    private static void attach(InteractiveFlowRegistry registry, TestFlowServer sympauthy) {
        registry.attach(sympauthy.baseUrl(), sympauthy.baseUrl() + "/.well-known/openid-configuration");
    }

    private static String discovery(String base) {
        return "{\"issuer\":\"" + base + "\","
                + "\"authorization_endpoint\":\"" + base + "/api/oauth2/authorize\","
                + "\"token_endpoint\":\"" + base + "/api/oauth2/token\"}";
    }

    private static String redirectTo(String url) {
        return "{\"redirect_url\":\"" + url + "\"}";
    }
}
