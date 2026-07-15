package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that start a real SympAuthy container (default: in-memory H2) and verify the
 * configuration mechanisms actually take effect. Requires Docker; tagged {@code integration} so
 * these are excluded from the default {@code test} task and run via {@code ./gradlew integrationTest}.
 *
 * <p>The observable signal used throughout is the {@code email_verified} claim: it is <em>absent</em>
 * from a minimal container's discovery document and <em>present</em> once the {@code email} claim is
 * enabled &mdash; and, unlike the bare string {@code email} (which the {@code email} scope already
 * contributes), it does not appear unless the claim was genuinely applied. Asserting on it therefore
 * proves the configuration reached the server rather than merely that the container booted.
 */
@Tag("integration")
class SympauthyContainerIT {

    /** Present in discovery only when the {@code email} claim has been enabled. */
    private static final String EMAIL_CLAIM_MARKER = "email_verified";

    /** Config (as a nested map / YAML) that enables password auth with an email identifier. */
    private static final Map<String, Object> EMAIL_PASSWORD_CONFIG = Map.of(
            "auth", Map.of(
                    "by-password", Map.of("enabled", true),
                    "identifier-claims", List.of("email")),
            "claims", Map.of("email", Map.of("enabled", true)));

    private static final String EMAIL_PASSWORD_YAML = String.join(
            "\n",
            "auth:",
            "  by-password:",
            "    enabled: true",
            "  identifier-claims:",
            "    - email",
            "claims:",
            "  email:",
            "    enabled: true",
            "");

    /** Fetches the discovery document, asserting it is served and advertises the pinned issuer. */
    private static String fetchDiscovery(SympauthyContainer sympauthy) throws Exception {
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

    @Test
    void servesDiscoveryWithMinimalDefaults() throws Exception {
        try (SympauthyContainer sympauthy = new SympauthyContainer()) {
            sympauthy.start();

            String discovery = fetchDiscovery(sympauthy);
            assertFalse(
                    discovery.contains(EMAIL_CLAIM_MARKER),
                    "the email claim is opt-in and must be absent from a minimal container");
        }
    }

    @Test
    void appliesProgramArgumentOverrides() throws Exception {
        try (SympauthyContainer sympauthy = new SympauthyContainer()
                .withEnvironments("default", "admin")
                .withProperties(Map.of("claims.email.enabled", "true"))) {
            sympauthy.start();

            String discovery = fetchDiscovery(sympauthy);
            assertTrue(
                    discovery.contains(EMAIL_CLAIM_MARKER),
                    "withProperties(...) should enable the email claim via program arguments");
        }
    }

    @Test
    void appliesNestedConfigMap() throws Exception {
        try (SympauthyContainer sympauthy = new SympauthyContainer()
                .withConfig(EMAIL_PASSWORD_CONFIG)) {
            sympauthy.start();

            String discovery = fetchDiscovery(sympauthy);
            assertTrue(
                    discovery.contains(EMAIL_CLAIM_MARKER),
                    "withConfig(...) should mount a JSON config file, preserving nested lists");
        }
    }

    @Test
    void appliesMountedConfigFile() throws Exception {
        try (SympauthyContainer sympauthy = new SympauthyContainer()
                .withYamlConfig(EMAIL_PASSWORD_YAML)) {
            sympauthy.start();

            String discovery = fetchDiscovery(sympauthy);
            assertTrue(
                    discovery.contains(EMAIL_CLAIM_MARKER),
                    "the mounted YAML should be loaded via MICRONAUT_CONFIG_FILES");
        }
    }
}
