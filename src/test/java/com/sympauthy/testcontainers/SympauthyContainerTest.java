package com.sympauthy.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration tests for {@link SympauthyContainer}. These do not start Docker &mdash; they call
 * the (Docker-free) {@link SympauthyContainer#configure()} and assert the resulting program
 * arguments ({@link SympauthyContainer#getCommandParts()}) and environment
 * ({@link SympauthyContainer#getEnv()}) are wired correctly. Container-starting checks live in the
 * {@code *IT} classes of the separate {@code integrationTest} source set.
 *
 * <p>Note: {@code GenericContainer#getDockerImageName()} resolves (pulls) the image in
 * Testcontainers 2.x, so it is deliberately not called here &mdash; the default image is asserted
 * via the static constant instead.
 */
class SympauthyContainerTest {

    /** Runs the Docker-free {@code configure()} and returns the generated program arguments. */
    private static List<String> commandOf(SympauthyContainer container) {
        container.configure();
        return Arrays.asList(container.getCommandParts());
    }

    /** Returns the {@code KEY=value} environment entry for {@code key}, or {@code null}. */
    private static String envValue(SympauthyContainer container, String key) {
        container.configure();
        return container.getEnv().stream()
                .filter(entry -> entry.startsWith(key + "="))
                .map(entry -> entry.substring(key.length() + 1))
                .findFirst()
                .orElse(null);
    }

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

    @Test
    void isMinimalByDefault() {
        SympauthyContainer container = new SympauthyContainer();

        List<String> command = commandOf(container);
        // Only the default environment; the admin environment is now opt-in.
        assertEquals("default", envValue(container, "MICRONAUT_ENVIRONMENTS"));
        // The in-memory H2 datasource and the pinned URLs are always present...
        assertTrue(
                command.contains("-r2dbc.datasources.default.url=" + SympauthyContainer.IN_MEMORY_H2_URL),
                command.toString());
        assertTrue(command.contains("-auth.issuer=" + container.getIssuerUrl()), command.toString());
        assertTrue(command.contains("-urls.root=" + container.getIssuerUrl()), command.toString());
        // ...but the previously baked-in auth/claim conveniences are not.
        assertFalse(command.contains("-auth.by-password.enabled=true"), command.toString());
        assertFalse(command.contains("-auth.identifier-claims=email"), command.toString());
        assertFalse(command.contains("-claims.email.enabled=true"), command.toString());
        // No config files mounted by default.
        assertEquals(null, envValue(container, "MICRONAUT_CONFIG_FILES"));
    }

    @Test
    void exposesPropertiesAsProgramArguments() {
        SympauthyContainer container = new SympauthyContainer()
                .withProperty("auth.by-password.enabled", "true")
                .withProperties(Map.of("claims.email.enabled", "true"));

        List<String> command = commandOf(container);
        assertTrue(command.contains("-auth.by-password.enabled=true"), command.toString());
        assertTrue(command.contains("-claims.email.enabled=true"), command.toString());
    }

    @Test
    void serializesNestedMapsAndListsToJson() {
        Map<String, Object> byPassword = new LinkedHashMap<>();
        byPassword.put("enabled", true);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("by-password", byPassword);
        auth.put("identifier-claims", List.of("email"));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("scopes", List.of("admin"));
        rule.put("behavior", "grant");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("auth", auth);
        config.put("rules", List.of(rule)); // a list of objects — exactly what flattening handled poorly

        assertEquals(
                "{\"auth\":{\"by-password\":{\"enabled\":true},\"identifier-claims\":[\"email\"]},"
                        + "\"rules\":[{\"scopes\":[\"admin\"],\"behavior\":\"grant\"}]}",
                SympauthyContainer.toJson(config));
    }

    @Test
    void escapesJsonStringValues() {
        assertEquals(
                "{\"note\":\"a\\\"b\\\\c\\nd\"}",
                SympauthyContainer.toJson(Map.of("note", "a\"b\\c\nd")));
    }

    @Test
    void nestedConfigIsMountedAsJsonFile() {
        SympauthyContainer container = new SympauthyContainer()
                .withConfig(Map.of("claims", Map.of("email", Map.of("enabled", true))));

        // withConfig no longer emits program arguments; it mounts a JSON config file instead.
        List<String> command = commandOf(container);
        assertFalse(command.contains("-claims.email.enabled=true"), command.toString());
        assertEquals("/config/inline-0.json", envValue(container, "MICRONAUT_CONFIG_FILES"));
    }

    @Test
    void datasourceOverridesTheDefault() {
        SympauthyContainer container = new SympauthyContainer()
                .withDatasource("r2dbc:postgresql://db:5432/sympauthy", "user", "pass");

        List<String> command = commandOf(container);
        assertTrue(
                command.contains("-r2dbc.datasources.default.url=r2dbc:postgresql://db:5432/sympauthy"),
                command.toString());
        assertTrue(command.contains("-r2dbc.datasources.default.username=user"), command.toString());
        assertTrue(command.contains("-r2dbc.datasources.default.password=pass"), command.toString());
        // The default H2 URL must have been replaced, not appended.
        assertFalse(
                command.contains("-r2dbc.datasources.default.url=" + SympauthyContainer.IN_MEMORY_H2_URL),
                command.toString());
    }

    @Test
    void containerPinsAlwaysOverrideCallerSuppliedUrls() {
        SympauthyContainer container = new SympauthyContainer()
                .withProperty("urls.root", "http://not-reachable.example")
                .withProperty("auth.issuer", "http://not-reachable.example");

        List<String> command = commandOf(container);
        assertTrue(command.contains("-urls.root=" + container.getIssuerUrl()), command.toString());
        assertTrue(command.contains("-auth.issuer=" + container.getIssuerUrl()), command.toString());
        assertFalse(command.contains("-urls.root=http://not-reachable.example"), command.toString());
        assertFalse(command.contains("-auth.issuer=http://not-reachable.example"), command.toString());
    }

    @Test
    void environmentsReplaceTheDefaultSet() {
        SympauthyContainer container = new SympauthyContainer()
                .withEnvironments("default", "admin", "google");

        assertEquals("default,admin,google", envValue(container, "MICRONAUT_ENVIRONMENTS"));
    }

    @Test
    void inlineConfigContentPopulatesConfigFilesEnv() {
        SympauthyContainer container = new SympauthyContainer()
                .withYamlConfig("auth:\n  by-password:\n    enabled: true\n")
                .withJsonConfig("{\"claims\":{\"email\":{\"enabled\":true}}}");

        String configFiles = envValue(container, "MICRONAUT_CONFIG_FILES");
        assertEquals("/config/inline-0.yml,/config/inline-1.json", configFiles);
    }

    @Test
    void mountedConfigFilePopulatesConfigFilesEnv() throws Exception {
        Path file = Files.createTempFile("sympauthy-config", ".yml");
        SympauthyContainer container = new SympauthyContainer()
                .withConfigFile(MountableFile.forHostPath(file));

        String configFiles = envValue(container, "MICRONAUT_CONFIG_FILES");
        assertTrue(configFiles.startsWith("/config/0-"), configFiles);
        assertTrue(configFiles.endsWith(file.getFileName().toString()), configFiles);
    }
}
