package com.sympauthy.testcontainers;

import com.sympauthy.testcontainers.flow.InteractiveFlow;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link GenericContainer} that runs <a href="https://sympauthy.github.io/">SympAuthy</a>,
 * a self-hosted OAuth 2.1 / OpenID Connect authorization server, so it can be controlled from
 * unit and integration tests.
 *
 * <p>SympAuthy is heavily configuration-driven. Rather than a typed method per settings section,
 * this container exposes SympAuthy's full configuration surface through a few generic escape
 * hatches that map onto the three mechanisms the server understands:
 *
 * <ul>
 *   <li><b>Micronaut program arguments</b> ({@code -<key>=<value>}) &mdash;
 *       {@link #withProperty(String, String)} and {@link #withProperties(Map)}, for targeted
 *       scalar overrides.</li>
 *   <li><b>Micronaut environment profiles</b> ({@code MICRONAUT_ENVIRONMENTS}) &mdash;
 *       {@link #withEnvironments(String...)} (profiles such as {@code default}, {@code by-mail},
 *       {@code admin}, or a well-known provider like {@code google}).</li>
 *   <li><b>External YAML/JSON config files</b> ({@code MICRONAUT_CONFIG_FILES}) &mdash;
 *       {@link #withConfig(Map)} (a nested map serialized to a JSON file, so lists and nested
 *       objects such as {@code rules} are preserved), {@link #withConfigFile(MountableFile)},
 *       {@link #withConfigContent(String, ConfigFormat)} and the {@link #withYamlConfig(String)} /
 *       {@link #withJsonConfig(String)} shortcuts, for supplying configuration in bulk.</li>
 * </ul>
 *
 * <p><b>Configuration precedence</b> (highest wins): the container-managed issuer/root URL &gt;
 * program-argument overrides ({@code withProperty}/{@code withProperties}) &gt;
 * {@code MICRONAUT_ENVIRONMENTS} profiles &gt; mounted config files ({@code withConfig},
 * {@code withConfigFile}, {@code withConfigContent}; later files override earlier ones) &gt; the
 * image's bundled defaults. The two things the container always owns are
 * {@code auth.issuer} and {@code urls.root}: they are pinned to a host-reachable URL so tests can
 * reach the issuer, and they override anything the caller sets.
 *
 * <p><b>Minimal by default.</b> Out of the box the container only runs the {@code default}
 * environment against an in-memory H2 database (schema created automatically by SympAuthy's Flyway
 * migrations) and pins the issuer. Password authentication, claims, the {@code admin} environment,
 * mail, clients, providers, etc. are all opt-in &mdash; enable exactly what a test needs. Point the
 * container at an external database with {@link #withDatasource(String, String, String)}.
 *
 * <pre>{@code
 * try (SympauthyContainer sympauthy = new SympauthyContainer()
 *         .withEnvironments("default", "admin")
 *         .withConfig(Map.of(
 *                 "auth", Map.of(
 *                         "by-password", Map.of("enabled", true),
 *                         "identifier-claims", List.of("email")),
 *                 "claims", Map.of("email", Map.of("enabled", true))))) {
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

    /** The Micronaut environment enabled by default: baseline config, no insecure features. */
    static final String DEFAULT_ENVIRONMENT = "default";

    /** In-memory H2 datasource used when no external datasource is configured. */
    static final String IN_MEMORY_H2_URL =
            "r2dbc:h2:mem:///sympauthy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    static final String IN_MEMORY_H2_USERNAME = "sa";

    static final String IN_MEMORY_H2_PASSWORD = "";

    /** In-container directory the mounted/inline config files are copied to. */
    static final String CONFIG_DIR = "/config";

    /**
     * A host port pinned for this instance. SympAuthy bakes the issuer/discovery URLs into its
     * startup configuration, so the host-side port must be known up front. Allocating a free port
     * per instance keeps the container reachable from the host while still allowing several
     * instances to run in parallel.
     */
    private final int hostPort;

    /**
     * Micronaut property overrides, materialized as {@code -<key>=<value>} program arguments in
     * {@link #configure()}. A map (rather than a list) guarantees a single value per key, so the
     * container-managed pins can deterministically override anything the caller set. Insertion
     * order is preserved for stable, readable command lines.
     */
    private final Map<String, String> properties = new LinkedHashMap<>();

    /** Micronaut environment profiles, joined into {@code MICRONAUT_ENVIRONMENTS}. */
    private final List<String> environments = new ArrayList<>();

    /** In-container paths of mounted config files, joined into {@code MICRONAUT_CONFIG_FILES}. */
    private final List<String> configFilePaths = new ArrayList<>();

    /** Monotonic counter used to give each mounted config file a unique in-container name. */
    private int configFileCounter = 0;

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

        environments.add(DEFAULT_ENVIRONMENT);
        withDatasource(IN_MEMORY_H2_URL, IN_MEMORY_H2_USERNAME, IN_MEMORY_H2_PASSWORD);

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
     * <p>Equivalent to setting the {@code r2dbc.datasources.default.*} properties directly.
     *
     * @param r2dbcUrl  the R2DBC connection URL
     * @param username  the datasource username
     * @param password  the datasource password
     * @return this container, for chaining
     */
    public SympauthyContainer withDatasource(String r2dbcUrl, String username, String password) {
        return withProperty("r2dbc.datasources.default.url", r2dbcUrl)
                .withProperty("r2dbc.datasources.default.username", username)
                .withProperty("r2dbc.datasources.default.password", password);
    }

    /**
     * Overrides a single Micronaut configuration property, passed to SympAuthy as a
     * {@code -<key>=<value>} program argument (e.g. {@code auth.by-password.enabled}, {@code true}).
     * The last value set for a given key wins. Array elements use indexed keys, e.g.
     * {@code auth.identifier-claims[0]}.
     *
     * @param key   the dotted Micronaut property key
     * @param value the value
     * @return this container, for chaining
     */
    public SympauthyContainer withProperty(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Overrides several Micronaut configuration properties at once. See
     * {@link #withProperty(String, String)}.
     *
     * @param props a map of dotted property keys to values
     * @return this container, for chaining
     */
    public SympauthyContainer withProperties(Map<String, String> props) {
        props.forEach(this::withProperty);
        return this;
    }

    /**
     * Supplies configuration as a nested map mirroring SympAuthy's YAML/JSON structure. The map is
     * serialized to a JSON file and mounted like {@link #withJsonConfig(String)}, so lists and
     * nested objects &mdash; e.g. {@code rules}, providers, clients &mdash; are represented natively
     * instead of being flattened into brittle indexed program arguments. {@link Number}s and
     * {@link Boolean}s are written as JSON literals; every other scalar is quoted via its
     * {@code toString()} form. No JSON library is involved.
     *
     * <pre>{@code
     * withConfig(Map.of(
     *         "auth", Map.of(
     *                 "by-password", Map.of("enabled", true),
     *                 "identifier-claims", List.of("email")),
     *         "claims", Map.of("email", Map.of("enabled", true))));
     * }</pre>
     *
     * @param config a nested configuration map
     * @return this container, for chaining
     */
    public SympauthyContainer withConfig(Map<String, ?> config) {
        return withConfigContent(toJson(config), ConfigFormat.JSON);
    }

    /**
     * Replaces the set of Micronaut environment profiles ({@code MICRONAUT_ENVIRONMENTS}). Common
     * profiles are {@code default} (baseline), {@code by-mail} (email/password auth), {@code admin}
     * (Admin API and UI, a pre-provisioned admin client) and well-known providers like
     * {@code google}. Defaults to {@code default} alone.
     *
     * @param envs the environment profiles, in order
     * @return this container, for chaining
     */
    public SympauthyContainer withEnvironments(String... envs) {
        environments.clear();
        for (String env : envs) {
            environments.add(env);
        }
        return this;
    }

    /**
     * Mounts a YAML or JSON configuration file into the container and adds it to
     * {@code MICRONAUT_CONFIG_FILES}. The file's extension is preserved so Micronaut infers the
     * format. Provide the file with {@link MountableFile#forClasspathResource(String)} or
     * {@link MountableFile#forHostPath(String)}. Files added later override earlier ones.
     *
     * @param file the config file to mount
     * @return this container, for chaining
     */
    public SympauthyContainer withConfigFile(MountableFile file) {
        String basename = Paths.get(file.getResolvedPath()).getFileName().toString();
        String target = CONFIG_DIR + "/" + (configFileCounter++) + "-" + basename;
        withCopyFileToContainer(file, target);
        configFilePaths.add(target);
        return this;
    }

    /**
     * Writes inline configuration content into the container as a config file and adds it to
     * {@code MICRONAUT_CONFIG_FILES}. Files added later override earlier ones.
     *
     * @param content the raw YAML or JSON content
     * @param format  the content's format (drives the file extension)
     * @return this container, for chaining
     */
    public SympauthyContainer withConfigContent(String content, ConfigFormat format) {
        String target = CONFIG_DIR + "/inline-" + (configFileCounter++) + "." + format.extension;
        withCopyToContainer(Transferable.of(content), target);
        configFilePaths.add(target);
        return this;
    }

    /**
     * Shortcut for {@link #withConfigContent(String, ConfigFormat) withConfigContent(yaml,
     * ConfigFormat.YAML)}.
     *
     * @param yaml the raw YAML content
     * @return this container, for chaining
     */
    public SympauthyContainer withYamlConfig(String yaml) {
        return withConfigContent(yaml, ConfigFormat.YAML);
    }

    /**
     * Shortcut for {@link #withConfigContent(String, ConfigFormat) withConfigContent(json,
     * ConfigFormat.JSON)}.
     *
     * @param json the raw JSON content
     * @return this container, for chaining
     */
    public SympauthyContainer withJsonConfig(String json) {
        return withConfigContent(json, ConfigFormat.JSON);
    }

    /**
     * Wires an {@link InteractiveFlow} mock frontend into this container: SympAuthy is pointed at the
     * flow's pages and callback, and the flow is told this container's URLs so
     * {@link InteractiveFlow#run()} can drive it after {@link #start()}. Configure the authentication
     * method (e.g. password) and claims separately, as usual.
     *
     * <p>The flow's {@code clients.<id>} and {@code flows.<id>} config is applied via
     * {@link #withProperties(Map)} (program-argument overrides), so it takes precedence over any
     * client/flow config the caller supplied through config files or environment profiles and does not
     * erase the rest of their configuration.
     *
     * <p>Because the flow's page URLs must be baked into SympAuthy's startup configuration, create the
     * flow (with {@link InteractiveFlow#forClient(String)}) <em>before</em> calling this.
     *
     * @param flow the mock flow frontend
     * @return this container, for chaining
     */
    public SympauthyContainer withFlow(InteractiveFlow flow) {
        flow.attach(getBaseUrl(), getOpenIdConfigurationUrl());
        return withProperties(flow.containerProperties());
    }

    @Override
    protected void configure() {
        withEnv("MICRONAUT_ENVIRONMENTS", String.join(",", environments));
        if (!configFilePaths.isEmpty()) {
            withEnv("MICRONAUT_CONFIG_FILES", String.join(",", configFilePaths));
        }

        // Micronaut reads "-property=value" program arguments as configuration overrides, appended
        // to the image entrypoint. The container-managed issuer/root URL pins are applied last so
        // they always win over caller-supplied properties and keep the instance host-reachable.
        Map<String, String> effective = new LinkedHashMap<>(properties);
        effective.put("auth.issuer", getIssuerUrl());
        effective.put("urls.root", getIssuerUrl());

        List<String> args = new ArrayList<>(effective.size());
        effective.forEach((key, value) -> args.add("-" + key + "=" + value));
        withCommand(args.toArray(new String[0]));
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

    /**
     * Serializes a configuration value to a compact JSON document. Maps become objects, lists become
     * arrays, {@link Number}s and {@link Boolean}s become JSON literals, and every other value is
     * quoted via its {@code toString()} form. Keeping this in-house avoids pulling in a JSON library.
     */
    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                appendJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendJson(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            appendJsonString(sb, value.toString());
        }
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not allocate a free host port for SympAuthy", e);
        }
    }

    /** Format of an external SympAuthy configuration file. */
    public enum ConfigFormat {
        YAML("yml"),
        JSON("json");

        final String extension;

        ConfigFormat(String extension) {
            this.extension = extension;
        }
    }
}
