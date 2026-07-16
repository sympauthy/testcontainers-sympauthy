package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overrides supplied via {@link SympauthyContainer#withProperties(Map)} reach the server as program
 * arguments: enabling the {@code email} claim makes {@code email_verified} appear in discovery.
 */
class ProgramArgumentsIT extends AbstractSympauthyContainerIT {

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
}
