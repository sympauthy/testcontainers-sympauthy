package com.sympauthy.testcontainers;

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
abstract class AbstractSympauthyContainerIT {

    /** Present in discovery only when the {@code email} claim has been enabled. */
    protected static final String EMAIL_CLAIM_MARKER = "email_verified";

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
}
