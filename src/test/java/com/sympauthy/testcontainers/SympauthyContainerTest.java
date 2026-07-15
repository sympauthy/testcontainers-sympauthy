package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration tests for {@link SympauthyContainer}. These do not start Docker — they only assert
 * that the container is wired up correctly. A container-starting integration test will be added once
 * the SympAuthy image coordinates and readiness contract are confirmed.
 */
class SympauthyContainerTest {

    @Test
    void exposesTheSympauthyHttpPort() {
        SympauthyContainer container = new SympauthyContainer();

        assertEquals(8080, SympauthyContainer.SYMPAUTHY_PORT);
        assertTrue(
                container.getExposedPorts().contains(SympauthyContainer.SYMPAUTHY_PORT),
                "container should expose the SympAuthy HTTP port");
    }

    @Test
    void acceptsACustomImageReference() {
        SympauthyContainer container = new SympauthyContainer("ghcr.io/sympauthy/sympauthy:latest");

        assertTrue(container.getExposedPorts().contains(SympauthyContainer.SYMPAUTHY_PORT));
    }
}
