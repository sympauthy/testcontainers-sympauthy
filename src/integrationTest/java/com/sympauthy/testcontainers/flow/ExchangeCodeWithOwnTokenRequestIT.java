package com.sympauthy.testcontainers.flow;

import com.sympauthy.testcontainers.AbstractSympauthyContainerIT;
import com.sympauthy.testcontainers.Client;
import com.sympauthy.testcontainers.SympauthyContainer;
import com.sympauthy.testcontainers.internal.json.JsonCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exchanges the code of a {@link InteractiveFlow#run() run} through a token request the test builds
 * itself, rather than {@link AuthorizationResult#exchange()}. The token endpoint refuses a code whose
 * PKCE verifier is missing or wrong, so tokens coming back prove {@link AuthorizationResult#codeVerifier()}
 * is the verifier of the challenge the run's authorization request carried.
 *
 * <p>Both client kinds share one container, each on its own registry and flow: a public client sending
 * {@code client_id} alone, and a confidential one also sending its secret ({@code client_secret_post}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeCodeWithOwnTokenRequestIT extends AbstractSympauthyContainerIT {

    private static final String PASSWORD = "Str0ngP@ssw0rd!";

    private InteractiveFlowRegistry publicRegistry;
    private InteractiveFlowRegistry confidentialRegistry;
    private SympauthyContainer sympauthy;
    private String tokenEndpoint;

    @BeforeAll
    void startContainer() throws Exception {
        publicRegistry = InteractiveFlowRegistry.forClient(Client.publicClient("public-app"))
                .withFlowId("public")
                .withScopes("openid");
        confidentialRegistry = InteractiveFlowRegistry
                .forClient(Client.confidentialClient("confidential-app", "s3cr3t-app"))
                .withFlowId("confidential")
                .withScopes("openid");
        sympauthy = newContainer()
                .withConfig(config())
                .withFlows(publicRegistry)
                .withFlows(confidentialRegistry);
        try {
            sympauthy.start();
            tokenEndpoint = (String) JsonCodec.parseObject(fetchDiscovery(sympauthy)).get("token_endpoint");
        } catch (Throwable failure) {
            dumpLogs();
            throw failure;
        }
    }

    @AfterAll
    void stopContainer() {
        if (sympauthy != null) {
            sympauthy.close();
        }
        if (publicRegistry != null) {
            publicRegistry.close();
        }
        if (confidentialRegistry != null) {
            confidentialRegistry.close();
        }
    }

    @Test
    void aPublicClientExchangesTheCodeWithTheVerifierAlone() throws Exception {
        try {
            AuthorizationResult result = signUp(publicRegistry, "public@example.com");

            Map<String, String> form = codeGrant(publicRegistry, result);
            form.put("client_id", publicRegistry.clientId());
            assertTokens(postToken(form));
        } catch (Throwable failure) {
            dumpLogs();
            throw failure;
        }
    }

    @Test
    void aConfidentialClientExchangesTheCodeWithTheVerifierAndItsSecret() throws Exception {
        try {
            AuthorizationResult result = signUp(confidentialRegistry, "confidential@example.com");

            Map<String, String> form = codeGrant(confidentialRegistry, result);
            form.put("client_id", confidentialRegistry.clientId());
            form.put("client_secret", confidentialRegistry.clientSecret());
            assertTokens(postToken(form));
        } catch (Throwable failure) {
            dumpLogs();
            throw failure;
        }
    }

    private static AuthorizationResult signUp(InteractiveFlowRegistry registry, String email) {
        AuthorizationResult result = registry.newFlow()
                .withSignUpHandler(resource -> Map.of("email", email, "password", PASSWORD))
                .run();
        assertNotNull(result.codeVerifier(), "the run should answer the verifier of its authorization request");
        return result;
    }

    /** The authorization-code grant of {@code result}, as the run's own exchange would send it — minus client authentication. */
    private static Map<String, String> codeGrant(InteractiveFlowRegistry registry, AuthorizationResult result) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", result.code());
        form.put("redirect_uri", registry.redirectUri());
        form.put("code_verifier", result.codeVerifier());
        return form;
    }

    private HttpResponse<String> postToken(Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertTokens(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), "the token endpoint should accept the code: " + response.body());
        Map<String, Object> tokens = JsonCodec.parseObject(response.body());
        assertNotNull(tokens.get("access_token"), "the exchange should yield an access token: " + response.body());
        assertNotNull(tokens.get("id_token"), "the openid scope should yield an id_token: " + response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Password auth with an email identifier, plus the two clients — each wired to its registry's callback and flow. */
    private Map<String, Object> config() {
        return Map.of(
                "auth", Map.of(
                        "by-password", Map.of("enabled", true),
                        "identifier-claims", List.of("email")),
                "claims", Map.of("email", Map.of("enabled", true)),
                "clients", Map.of(
                        publicRegistry.clientId(), Map.of(
                                "public", true,
                                "authorizationFlow", publicRegistry.flowId(),
                                "allowed-grant-types", List.of("authorization_code"),
                                "allowed-scopes", List.of("openid"),
                                "default-scopes", List.of("openid"),
                                "allowed-redirect-uris", List.of(publicRegistry.redirectUri())),
                        confidentialRegistry.clientId(), Map.of(
                                "secret", confidentialRegistry.clientSecret(),
                                "authorizationFlow", confidentialRegistry.flowId(),
                                "allowed-grant-types", List.of("authorization_code"),
                                "allowed-scopes", List.of("openid"),
                                "default-scopes", List.of("openid"),
                                "allowed-redirect-uris", List.of(confidentialRegistry.redirectUri()))));
    }

    private void dumpLogs() {
        System.out.println("=== SYMPAUTHY CONTAINER LOGS ===");
        try {
            System.out.println(sympauthy.getLogs());
        } catch (RuntimeException e) {
            System.out.println("(logs unavailable: " + e + ")");
        }
    }
}
