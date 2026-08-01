package com.sympauthy.testcontainers;

import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared support for the container-starting integration tests. Each scenario lives in its own
 * {@code *IT} subclass so it can carry whatever bespoke container setup it needs; the machinery
 * common to every scenario &mdash; fetching the discovery document and reasoning about the opt-in
 * {@code email_verified} claim &mdash; lives here.
 *
 * <p>These tests start a real SympAuthy container (default: in-memory H2) and verify that the
 * configuration mechanisms actually take effect. They require Docker and live in the dedicated
 * {@code integrationTest} source set (src/integrationTest/java), so they are excluded from the
 * default {@code test} task and run via {@code ./gradlew integrationTest}.
 *
 * <p>The observable signal used throughout is the {@code email_verified} claim: it is <em>absent</em>
 * from a minimal container's discovery document and <em>present</em> once the {@code email} claim is
 * enabled &mdash; and, unlike the bare string {@code email} (which the {@code email} scope already
 * contributes), it does not appear unless the claim was genuinely applied. Asserting on it therefore
 * proves the configuration reached the server rather than merely that the container booted.
 */
public abstract class AbstractSympauthyContainerIT {

    /** Present in discovery only when the {@code email} claim has been enabled. */
    protected static final String EMAIL_CLAIM_MARKER = "email_verified";

    /**
     * Creates a container from the default nightly image, or from the image named by the
     * {@code -Dsympauthy.image=<ref>} system property when set (e.g. a locally built {@code sympauthy:it}).
     * The override is marked as a compatible substitute so it passes the constructor's image check.
     */
    protected static SympauthyContainer newContainer() {
        String image = System.getProperty("sympauthy.image");
        return image == null
                ? new SympauthyContainer()
                : new SympauthyContainer(
                        DockerImageName.parse(image).asCompatibleSubstituteFor(SympauthyContainer.DEFAULT_IMAGE_NAME));
    }

    /** Fetches the discovery document, asserting it is served and advertises the pinned issuer. */
    protected static String fetchDiscovery(SympauthyContainer sympauthy) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(sympauthy.getOpenIdConfigurationUrl()))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(
                response.body().contains("\"issuer\""),
                "discovery document should advertise an issuer");
        assertTrue(
                response.body().contains(sympauthy.getIssuerUrl()),
                "issuer should match the container's URL");
        return response.body();
    }

    /**
     * Issues a GET against any API path on the container, optionally bearing an access token. Passing a
     * {@code null} token sends the request unauthenticated, so a test can assert an endpoint is genuinely
     * protected. Works for any bearer-authenticated API — the Admin API ({@code /api/v1/admin/*}), the
     * client API, and so on.
     *
     * @param sympauthy   the running container
     * @param path        the request path (e.g. {@code /api/v1/admin/users})
     * @param accessToken the bearer access token, or {@code null} to send no Authorization header
     * @return the HTTP response
     */
    protected static HttpResponse<String> apiGet(SympauthyContainer sympauthy, String path, String accessToken)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(sympauthy.getBaseUrl() + path))
                .header("Accept", "application/json")
                .GET();
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Issues a JSON POST against any API path on the container, optionally bearing an access token. The
     * POST sibling of {@link #apiGet(SympauthyContainer, String, String)} — used, for example, to call the
     * admin/client MFA-enrollment entry points that start a server-initiated interactive flow.
     *
     * @param sympauthy   the running container
     * @param path        the request path (e.g. {@code /api/v1/client/mfa/enrollment})
     * @param accessToken the bearer access token, or {@code null} to send no Authorization header
     * @param jsonBody    the JSON request body
     * @return the HTTP response
     */
    protected static HttpResponse<String> apiPost(SympauthyContainer sympauthy, String path, String accessToken,
            String jsonBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(sympauthy.getBaseUrl() + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
