package com.sympauthy.testcontainers.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the whole {@link InteractiveFlow} loop against the in-JVM {@link TestFlowServer} stub — no
 * Docker. Covers the happy paths (sign-up + claims, sign-in), auto-skip, the per-step callbacks, the
 * state-transport rules, PKCE on the authorize request, and the token exchange.
 */
class InteractiveFlowTest {

    private static final String FLOW_STATE = "flow-state-jwt";
    private static final String CODE = "auth-code-123";
    private static final String REDIRECT_URI = "https://client.example/callback";
    private static final String CLIENT_ID = "test-app";

    @Test
    void drivesSignUpThenClaimsToACodeAndTokens() {
        try (TestFlowServer server = new TestFlowServer()) {
            registerCommon(server);
            server.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo("/api/v1/flow/claims?state=" + FLOW_STATE)));
            server.route("GET", "/api/v1/flow/claims", request -> TestFlowServer.Response.json(200,
                    "{\"claims\":[{\"id\":\"given_name\",\"required\":true,\"name\":\"Given name\",\"type\":\"string\"}]}"));
            server.route("POST", "/api/v1/flow/claims", request ->
                    TestFlowServer.Response.json(200, redirectTo(REDIRECT_URI + "?code=" + CODE + "&state=abc")));

            List<FlowStep.Type> steps = new ArrayList<>();
            AuthorizationResult result = flow(server)
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"))
                    .withClaimsHandler(claims -> Map.of("given_name", "Ada"))
                    .withStepListener(step -> steps.add(step.type()))
                    .run();

            assertEquals(CODE, result.code());
            assertEquals(
                    List.of(FlowStep.Type.CONFIGURATION, FlowStep.Type.SIGN_UP, FlowStep.Type.CLAIMS,
                            FlowStep.Type.COMPLETED),
                    steps);

            // State transport: GET carries ?state=, POST carries the Authorization header.
            assertEquals(FLOW_STATE,
                    server.firstRequest("GET", "/api/v1/flow/configuration").stateQueryParam());
            assertEquals("State " + FLOW_STATE,
                    server.firstRequest("POST", "/api/v1/flow/sign-up").headers().get("Authorization"));
            TestFlowServer.RecordedRequest claimsPost = server.firstRequest("POST", "/api/v1/flow/claims");
            assertEquals("State " + FLOW_STATE, claimsPost.headers().get("Authorization"));
            assertTrue(claimsPost.body().contains("given_name"));

            // PKCE + OAuth params on the authorize request.
            String authorizeQuery = server.firstRequest("GET", "/api/oauth2/authorize").query();
            assertTrue(authorizeQuery.contains("response_type=code"));
            assertTrue(authorizeQuery.contains("client_id=test-app"));
            assertTrue(authorizeQuery.contains("code_challenge_method=S256"));
            assertTrue(authorizeQuery.contains("code_challenge="));

            // Token exchange.
            TokenResponse tokens = result.exchange();
            assertEquals("at", tokens.accessToken());
            assertEquals("it", tokens.idToken());
            assertEquals("Bearer", tokens.tokenType());
            assertEquals(3600L, tokens.expiresIn());
            String tokenBody = server.firstRequest("POST", "/api/oauth2/token").body();
            assertTrue(tokenBody.contains("grant_type=authorization_code"));
            assertTrue(tokenBody.contains("code=" + CODE));
            assertTrue(tokenBody.contains("code_verifier="));
            assertTrue(tokenBody.contains("client_id=test-app"));
        }
    }

    @Test
    void autoSkipsClaimsWhenNoneAreRequested() {
        try (TestFlowServer server = new TestFlowServer()) {
            registerCommon(server);
            server.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo("/api/v1/flow/claims?state=" + FLOW_STATE)));
            server.route("GET", "/api/v1/flow/claims", request ->
                    TestFlowServer.Response.json(200, redirectTo(REDIRECT_URI + "?code=" + CODE + "&state=abc")));

            AuthorizationResult result = flow(server)
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"))
                    .run();

            assertEquals(CODE, result.code());
        }
    }

    @Test
    void drivesSignInDirectlyToACode() {
        try (TestFlowServer server = new TestFlowServer()) {
            registerCommon(server);
            server.route("POST", "/api/v1/flow/sign-in", request ->
                    TestFlowServer.Response.json(200, redirectTo(REDIRECT_URI + "?code=" + CODE + "&state=abc")));

            List<FlowStep.Type> steps = new ArrayList<>();
            AuthorizationResult result = flow(server)
                    .withSignInHandler(configuration -> Credentials.of("ada@example.com", "pw"))
                    .withStepListener(step -> steps.add(step.type()))
                    .run();

            assertEquals(CODE, result.code());
            assertEquals(
                    List.of(FlowStep.Type.CONFIGURATION, FlowStep.Type.SIGN_IN, FlowStep.Type.COMPLETED),
                    steps);
            TestFlowServer.RecordedRequest signIn = server.firstRequest("POST", "/api/v1/flow/sign-in");
            assertTrue(signIn.body().contains("ada@example.com"));
            assertTrue(signIn.body().contains("pw"));
        }
    }

    @Test
    void failsWhenClaimsAreRequiredButNoHandlerIsConfigured() {
        try (TestFlowServer server = new TestFlowServer()) {
            registerCommon(server);
            server.route("POST", "/api/v1/flow/sign-up", request ->
                    TestFlowServer.Response.json(200, redirectTo("/api/v1/flow/claims?state=" + FLOW_STATE)));
            server.route("GET", "/api/v1/flow/claims", request -> TestFlowServer.Response.json(200,
                    "{\"claims\":[{\"id\":\"given_name\",\"required\":true,\"name\":\"Given name\",\"type\":\"string\"}]}"));

            InteractiveFlow flow = flow(server)
                    .withSignUpHandler(configuration -> Map.of("email", "ada@example.com", "password", "s3cret"));

            assertThrows(UnsupportedFlowStepException.class, flow::run);
        }
    }

    @Test
    void failsWhenClientIdIsMissing() {
        assertThrows(IllegalStateException.class, () ->
                InteractiveFlow.at("http://localhost:9")
                        .withRedirectUri(REDIRECT_URI)
                        .withSignUpHandler(configuration -> Map.of())
                        .run());
    }

    private InteractiveFlow flow(TestFlowServer server) {
        return InteractiveFlow.at(server.baseUrl())
                .withClientId(CLIENT_ID)
                .withRedirectUri(REDIRECT_URI)
                .withScopes("openid", "profile", "email");
    }

    private static void registerCommon(TestFlowServer server) {
        server.route("GET", "/.well-known/openid-configuration", request ->
                TestFlowServer.Response.json(200, discovery(server.baseUrl())));
        server.route("GET", "/api/oauth2/authorize", request ->
                TestFlowServer.Response.seeOther("/flow/ui?state=" + FLOW_STATE));
        server.route("GET", "/api/v1/flow/configuration", request -> TestFlowServer.Response.json(200,
                "{\"features\":{\"password_sign_in\":true,\"sign_up_enabled\":true},"
                        + "\"password\":{\"identifier_claims\":[\"email\"]},\"claims\":[]}"));
        server.route("POST", "/api/oauth2/token", request -> TestFlowServer.Response.json(200,
                "{\"access_token\":\"at\",\"id_token\":\"it\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600,\"scope\":\"openid\"}"));
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
