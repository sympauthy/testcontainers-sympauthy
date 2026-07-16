package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nested configuration map passed to {@link SympauthyContainer#withConfig(Map)} is mounted as a
 * JSON config file (preserving nested lists) and takes effect: {@code email_verified} appears in
 * discovery.
 */
class NestedConfigMapIT extends AbstractSympauthyContainerIT {

    /** Config (as a nested map / YAML) that enables password auth with an email identifier. */
    private static final Map<String, Object> EMAIL_PASSWORD_CONFIG = Map.of(
            "auth", Map.of(
                    "by-password", Map.of("enabled", true),
                    "identifier-claims", List.of("email")),
            "claims", Map.of("email", Map.of("enabled", true)));

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
}
