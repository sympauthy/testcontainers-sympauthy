package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inline YAML supplied to {@link SympauthyContainer#withYamlConfig(String)} is written to a mounted
 * file and loaded via {@code MICRONAUT_CONFIG_FILES}: {@code email_verified} appears in discovery.
 */
class InlineYamlConfigIT extends AbstractSympauthyContainerIT {

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
