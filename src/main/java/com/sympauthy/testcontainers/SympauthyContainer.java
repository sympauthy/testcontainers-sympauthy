package com.sympauthy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * A {@link GenericContainer} that runs <a href="https://sympauthy.github.io/">SympAuthy</a>,
 * a self-hosted OAuth 2.1 / OpenID Connect authorization server, so it can be controlled from
 * unit and integration tests.
 *
 * <p>By default the container is fully standalone: it runs against an in-memory H2 database (schema
 * created automatically by SympAuthy's Flyway migrations), enables password authentication with an
 * {@code email} identifier, and advertises an issuer that is reachable from host-side test code.
 * Point it at an external database (PostgreSQL or a custom H2) with
 * {@link #withDatasource(String, String, String)}.
 *
 * <pre>{@code
 * try (SympauthyContainer sympauthy = new SympauthyContainer()) {
 *     sympauthy.start();
 *     String discovery = sympauthy.getOpenIdConfigurationUrl();
 *     // ... point the system under test at sympauthy.getIssuerUrl()
 * }
 * }</pre>
 *
 * <p>Only a {@code nightly} image is currently published, so the default tag is {@code latest} of
 * {@code ghcr.io/sympauthy/sympauthy-nightly}. A local (host) Docker daemon is assumed, since the
 * issuer/discovery URLs are pinned to {@code http://localhost:<port>}.
 */
public class SympauthyContainer extends GenericContainer<SympauthyContainer> {

    static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/sympauthy/sympauthy-nightly");

    static final String DEFAULT_TAG = "latest";

    /** The HTTP port SympAuthy's server listens on inside the container. */
    public static final int SYMPAUTHY_PORT = 8080;

    /** In-memory H2 datasource used when no external datasource is configured. */
    static final String IN_MEMORY_H2_URL =
            "r2dbc:h2:mem:///sympauthy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    static final String IN_MEMORY_H2_USERNAME = "sa";

    static final String IN_MEMORY_H2_PASSWORD = "";

    /**
     * A host port pinned for this instance. SympAuthy bakes the issuer/discovery URLs into its
     * startup configuration, so the host-side port must be known up front. Allocating a free port
     * per instance keeps the container reachable from the host while still allowing several
     * instances to run in parallel.
     */
    private final int hostPort;

    private String r2dbcUrl = IN_MEMORY_H2_URL;

    private String datasourceUsername = IN_MEMORY_H2_USERNAME;

    private String datasourcePassword = IN_MEMORY_H2_PASSWORD;

    /** Creates a container using the default nightly SympAuthy image and an in-memory H2 database. */
    public SympauthyContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    /**
     * Creates a container from an image reference such as
     * {@code "ghcr.io/sympauthy/sympauthy-nightly:latest"}.
     *
     * @param dockerImageName the full image reference, including tag
     */
    public SympauthyContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    /**
     * Creates a container from a {@link DockerImageName}.
     *
     * @param dockerImageName the image to run
     */
    public SympauthyContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

        this.hostPort = findFreePort();
        addFixedExposedPort(hostPort, SYMPAUTHY_PORT);

        waitingFor(
                Wait.forHttp("/.well-known/openid-configuration")
                        .forPort(SYMPAUTHY_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(120)));
    }

    /**
     * Points SympAuthy at an external database instead of the default in-memory H2 instance.
     * Accepts any Micronaut R2DBC URL, e.g. PostgreSQL ({@code r2dbc:postgresql://host:5432/db}) or
     * a custom H2 URL. The referenced database must be reachable from inside the container (for a
     * companion PostgreSQL container, put both on the same {@link org.testcontainers.containers.Network}
     * and use its network alias as the host).
     *
     * @param r2dbcUrl  the R2DBC connection URL
     * @param username  the datasource username
     * @param password  the datasource password
     * @return this container, for chaining
     */
    public SympauthyContainer withDatasource(String r2dbcUrl, String username, String password) {
        this.r2dbcUrl = r2dbcUrl;
        this.datasourceUsername = username;
        this.datasourcePassword = password;
        return this;
    }

    @Override
    protected void configure() {
        // "default" loads SympAuthy's baseline config; "admin" enables the self-configuring admin
        // client/UI (see application-admin.yml, which references urls.root set below).
        withEnv("MICRONAUT_ENVIRONMENTS", "default,admin");

        // Micronaut reads "-property=value" program arguments as configuration overrides. These are
        // appended to the image entrypoint exactly as in SympAuthy's documented `docker run`.
        withCommand(
                "-r2dbc.datasources.default.url=" + r2dbcUrl,
                "-r2dbc.datasources.default.username=" + datasourceUsername,
                "-r2dbc.datasources.default.password=" + datasourcePassword,
                "-auth.issuer=" + getIssuerUrl(),
                "-urls.root=" + getIssuerUrl(),
                "-auth.by-password.enabled=true",
                "-auth.identifier-claims=email",
                "-claims.email.enabled=true");
    }

    /**
     * Returns the base HTTP URL of this SympAuthy instance, reachable from the host, e.g.
     * {@code http://localhost:49172}. Stable for the lifetime of the instance (it is pinned before
     * startup so it can be embedded in the issuer configuration).
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return "http://localhost:" + hostPort;
    }

    /**
     * Returns the OIDC issuer advertised by this instance (equal to {@link #getBaseUrl()} and to the
     * configured {@code urls.root}).
     *
     * @return the issuer URL
     */
    public String getIssuerUrl() {
        return getBaseUrl();
    }

    /**
     * Returns the URL of the OpenID Connect discovery document.
     *
     * @return the {@code /.well-known/openid-configuration} URL
     */
    public String getOpenIdConfigurationUrl() {
        return getBaseUrl() + "/.well-known/openid-configuration";
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not allocate a free host port for SympAuthy", e);
        }
    }
}
