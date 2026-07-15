package com.sympauthy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A {@link GenericContainer} that runs <a href="https://sympauthy.github.io/">SympAuthy</a>,
 * a self-hosted OAuth 2.1 / OpenID Connect authorization server, so it can be controlled from
 * unit and integration tests.
 *
 * <p>This is a scaffold: the default image coordinates, the readiness (wait) strategy and the
 * configuration entry points are placeholders and will be finalised once SympAuthy's runtime
 * contract (image name, startup configuration, health endpoint) is verified.
 *
 * <pre>{@code
 * try (SympauthyContainer sympauthy = new SympauthyContainer()) {
 *     sympauthy.start();
 *     String issuer = sympauthy.getBaseUrl();
 *     // ... point the system under test at `issuer`
 * }
 * }</pre>
 */
public class SympauthyContainer extends GenericContainer<SympauthyContainer> {

    // TODO(image): confirm the published image name/registry and pin a concrete default tag.
    static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("ghcr.io/sympauthy/sympauthy");

    static final String DEFAULT_TAG = "latest";

    /** The HTTP port SympAuthy's server listens on inside the container. */
    public static final int SYMPAUTHY_PORT = 8080;

    /** Creates a container using the default SympAuthy image and tag. */
    public SympauthyContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    /**
     * Creates a container from an image reference such as {@code "ghcr.io/sympauthy/sympauthy:1.0.0"}.
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

        withExposedPorts(SYMPAUTHY_PORT);
        // TODO(wait): refine to the real readiness signal once confirmed, e.g. an HTTP 200 from the
        // OIDC discovery document at /.well-known/openid-configuration. Waiting for the port to
        // accept connections is a safe placeholder that makes no assumptions about the HTTP surface.
        waitingFor(Wait.forListeningPort());
    }

    /**
     * Returns the base HTTP URL of the running SympAuthy instance (host and mapped port), for
     * example {@code http://localhost:32768}. Only valid after the container has started.
     *
     * @return the base URL reachable from the host
     */
    public String getBaseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(SYMPAUTHY_PORT);
    }
}
