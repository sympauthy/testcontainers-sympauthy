package com.sympauthy.testcontainers.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void drivesSignUpToACodeAndTokens() {
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
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
            assertEquals(FLOW_STATE, sympauthy.firstRequest("GET", "/api/v1/flow/configuration").stateQueryParam());
            assertEquals("State " + FLOW_STATE,
                    sympauthy.firstRequest("POST", "/api/v1/flow/sign-up").headers().get("Authorization"));

            TokenResponse tokens = result.exchange();
            assertEquals("at", tokens.accessToken());
            String tokenBody = sympauthy.firstRequest("POST", "/api/oauth2/token").body();
            assertTrue(tokenBody.contains("grant_type=authorization_code"));
            assertTrue(tokenBody.contains("code=" + CODE));
            assertTrue(tokenBody.contains("code_verifier="));
        }
    }

    @Test
    void drivesCollectClaimsPage() {
        // Also exercises StepListener: it observes every step as it happens (with its data) and stays
        // in sync with the flow's own stepTypes() history.
        List<FlowStep> observed = new ArrayList<>();
        try (TestFlowServer sympauthy = new TestFlowServer();
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
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
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("admin-app").withScopes("openid")) {
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
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
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
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
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
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
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
                InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow();

            registerSympAuthy(sympauthy, registry);
            attach(registry, sympauthy);

            assertThrows(FlowException.class, flow::run);
        }
    }

    @Test
    void failsWhenNotAttachedToAContainer() {
        try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient("test-app").withScopes("openid")) {
            InteractiveFlow flow = registry.newFlow().withSignUpHandler(configuration -> Map.of());
            assertThrows(IllegalStateException.class, flow::run);
        }
    }

    private static void registerSympAuthy(TestFlowServer sympauthy, InteractiveFlowRegistry registry) {
        sympauthy.route("GET", "/.well-known/openid-configuration", request ->
                TestFlowServer.Response.json(200, discovery(sympauthy.baseUrl())));
        // /authorize sends the browser to the mock frontend's sign-in page with a state token.
        sympauthy.route("GET", "/api/oauth2/authorize", request ->
                TestFlowServer.Response.seeOther(registry.frontendUrl() + "/sign-in?state=" + FLOW_STATE));
        sympauthy.route("GET", "/api/v1/flow/configuration", request -> TestFlowServer.Response.json(200,
                "{\"features\":{\"password_sign_in\":true,\"sign_up_enabled\":true},\"claims\":[]}"));
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
