package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A container booted with no configuration serves discovery, and the opt-in {@code email} claim is
 * absent &mdash; establishing the baseline the other scenarios opt into.
 */
class MinimalDefaultsIT extends AbstractSympauthyContainerIT {

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
}
