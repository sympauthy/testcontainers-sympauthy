package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration tests for {@link SympauthyContainer}. These do not start Docker — they only assert
 * that the container is wired up correctly. Container-starting checks live in
 * {@link SympauthyContainerIT} (tagged {@code integration}).
 *
 * <p>Note: {@code GenericContainer#getDockerImageName()} resolves (pulls) the image in
 * Testcontainers 2.x, so it is deliberately not called here — the default image is asserted via the
 * static constant instead.
 */
class SympauthyContainerTest {

    @Test
    void usesTheNightlyImageByDefault() {
        assertEquals(
                "ghcr.io/sympauthy/sympauthy-nightly",
                SympauthyContainer.DEFAULT_IMAGE_NAME.getUnversionedPart());
        assertEquals("latest", SympauthyContainer.DEFAULT_TAG);
    }

    @Test
    void derivesDiscoveryUrlFromTheIssuer() {
        SympauthyContainer container = new SympauthyContainer();

        assertEquals(8080, SympauthyContainer.SYMPAUTHY_PORT);
        assertTrue(container.getIssuerUrl().startsWith("http://localhost:"));
        assertEquals(container.getBaseUrl(), container.getIssuerUrl());
        assertEquals(
                container.getIssuerUrl() + "/.well-known/openid-configuration",
                container.getOpenIdConfigurationUrl());
    }

    @Test
    void acceptsACompatibleNightlyTag() {
        assertDoesNotThrow(
                () -> new SympauthyContainer("ghcr.io/sympauthy/sympauthy-nightly:pr-42"));
    }

    @Test
    void rejectsAnIncompatibleImage() {
        assertThrows(
                IllegalStateException.class,
                () -> new SympauthyContainer("ghcr.io/sympauthy/sympauthy:latest"));
    }
}
