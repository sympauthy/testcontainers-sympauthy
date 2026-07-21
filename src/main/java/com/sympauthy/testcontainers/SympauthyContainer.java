package com.sympauthy.testcontainers;

import com.sympauthy.testcontainers.flow.InteractiveFlowRegistry;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The Micronaut environment that turns on the Admin API/UI and its bundled resources. */
    static final String ADMIN_ENVIRONMENT = "admin";

    /** The audience the {@code admin} environment binds its API, claim and bootstrap invitation to. */
    static final String ADMIN_AUDIENCE = "admin";

    /** How long {@link #getBootstrapInvitationToken(String)} waits for the token to appear in the logs. */
    private static final Duration BOOTSTRAP_TOKEN_TIMEOUT = Duration.ofSeconds(10);

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

    /** Ids of bootstrap invitations declared via {@link #withBootstrapInvitation(String, String, Map)}. */
    private final Set<String> bootstrapInvitationIds = new LinkedHashSet<>();

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
     * Enables the {@code admin} Micronaut environment, which turns on SympAuthy's Admin API
     * ({@code /api/v1/admin/*}) and UI and ships a ready-made admin setup: an {@code admin} audience
     * (open sign-up disabled, invitations enabled), the {@code is_sympauthy_admin} boolean claim, a
     * scope-granting rule mapping that claim to every admin scope, a public {@code admin} client, and a
     * {@code first-admin} bootstrap invitation. Unlike {@link #withEnvironments(String...)}, this
     * <em>adds</em> {@code admin} to the active environments (keeping {@code default}) rather than
     * replacing them.
     *
     * <p>To actually reach the Admin API, create the first admin user by redeeming the
     * {@code first-admin} bootstrap invitation: read its token with
     * {@link #getBootstrapInvitationToken(String) getBootstrapInvitationToken("first-admin")} and drive
     * a sign-up through an {@link InteractiveFlowRegistry} whose client is wired with
     * {@link #withAdminClient(InteractiveFlowRegistry, String...)}, passing the token to
     * {@link com.sympauthy.testcontainers.flow.InteractiveFlow#withInvitationToken(String)}. The
     * resulting access token carries the admin scopes.
     *
     * @return this container, for chaining
     */
    public SympauthyContainer withAdmin() {
        if (!environments.contains(ADMIN_ENVIRONMENT)) {
            environments.add(ADMIN_ENVIRONMENT);
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
     * Wires an {@link InteractiveFlowRegistry} mock frontend into this container: SympAuthy's flow
     * definition is pointed at the registry's pages, and the registry is told this container's URLs so
     * its flows can be driven after {@link #start()}.
     *
     * <p>Only the {@code flows.<id>} definition is contributed here (applied via
     * {@link #withProperties(Map)}, so it wins over any flow config supplied through config files or
     * environment profiles without erasing the rest). The <b>client is yours to configure</b>: define a
     * {@code clients.<id>} whose id is {@link InteractiveFlowRegistry#clientId()}, whose
     * {@code authorizationFlow} is {@link InteractiveFlowRegistry#flowId()}, and whose
     * {@code allowed-redirect-uris} includes {@link InteractiveFlowRegistry#redirectUri()} — along with
     * the authentication method (e.g. password) and claims.
     *
     * <p>Because the flow's page URLs must be baked into SympAuthy's startup configuration, create the
     * registry (with {@link InteractiveFlowRegistry#forClient(Client)}) <em>before</em> calling this.
     *
     * @param registry the mock flow frontend
     * @return this container, for chaining
     */
    public SympauthyContainer withFlows(InteractiveFlowRegistry registry) {
        registry.attach(getBaseUrl(), getOpenIdConfigurationUrl());
        return withProperties(registry.flowProperties());
    }

    /**
     * Declares a <a href="https://sympauthy.github.io/functional/invitation.html#bootstrap-invitations">
     * bootstrap invitation</a>: an invitation SympAuthy creates at startup — as long as no user has yet
     * consented for {@code audience} — so the first user of that audience can self-register. Its token is
     * logged and can be read back with {@link #getBootstrapInvitationToken(String)} once the container
     * has started, then redeemed via the interactive flow.
     *
     * <p>The invitation is contributed as {@code invitations.<id>.*} program arguments. No
     * {@code url-template} is set, so the raw token is logged directly. The {@code admin} environment
     * ({@link #withAdmin()}) already ships a {@code first-admin} invitation, so you only need this for
     * additional or non-admin audiences.
     *
     * @param id       the invitation id (also its key under {@code invitations})
     * @param audience the audience the invitation binds the new user to
     * @param claims   custom claim values pre-assigned to the user on registration (e.g.
     *                 {@code Map.of("is_sympauthy_admin", "true")}); OpenID claims must come from the user
     * @return this container, for chaining
     */
    public SympauthyContainer withBootstrapInvitation(String id, String audience, Map<String, String> claims) {
        String prefix = "invitations." + id + ".";
        withProperty(prefix + "audience", audience);
        claims.forEach((name, value) -> withProperty(prefix + "claims." + name, value));
        bootstrapInvitationIds.add(id);
        return this;
    }

    /**
     * Declares a bootstrap invitation with no pre-assigned claims. See
     * {@link #withBootstrapInvitation(String, String, Map)}.
     *
     * @param id       the invitation id
     * @param audience the audience the invitation binds the new user to
     * @return this container, for chaining
     */
    public SympauthyContainer withBootstrapInvitation(String id, String audience) {
        return withBootstrapInvitation(id, audience, Map.of());
    }

    /**
     * Defines a public client, bound to the {@code admin} audience and wired to an
     * {@link InteractiveFlowRegistry} mock frontend, suitable for redeeming an admin bootstrap
     * invitation through the interactive flow. Pair it with {@link #withAdmin()} (which supplies the
     * {@code admin} audience, the {@code is_sympauthy_admin} claim and the scope-granting rule) and
     * {@link #withFlows(InteractiveFlowRegistry)}.
     *
     * <p>The generated {@code clients.<id>} (id {@link InteractiveFlowRegistry#clientId()}) uses the
     * authorization-code flow with PKCE, allows the registry's {@link InteractiveFlowRegistry#redirectUri()
     * redirect URI}, and both allows and defaults to {@code openid} plus the requested admin
     * {@code scopes} (e.g. {@code "admin:users:read"}). The registry is also told to request those same
     * scopes, so the authorize request and the client stay in sync.
     *
     * @param registry the mock flow frontend whose client this defines
     * @param scopes   the admin scopes the client (and flow) request, on top of {@code openid}
     * @return this container, for chaining
     */
    public SympauthyContainer withAdminClient(InteractiveFlowRegistry registry, String... scopes) {
        Set<String> scopeSet = new LinkedHashSet<>();
        scopeSet.add("openid");
        scopeSet.addAll(Arrays.asList(scopes));
        List<String> scopeList = new ArrayList<>(scopeSet);

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("audience", ADMIN_AUDIENCE);
        client.put("public", true);
        client.put("authorizationFlow", registry.flowId());
        client.put("allowed-grant-types", List.of("authorization_code"));
        client.put("allowed-redirect-uris", List.of(registry.redirectUri()));
        client.put("allowed-scopes", scopeList);
        client.put("default-scopes", scopeList);

        registry.withScopes(scopeList.toArray(new String[0]));
        return withConfig(Map.of("clients", Map.of(registry.clientId(), client)));
    }

    /**
     * Reads the raw token of a bootstrap invitation from this container's startup logs. Call after
     * {@link #start()}. SympAuthy logs the token when it creates the invitation, which happens once the
     * server is ready and only while no user has yet consented for the invitation's audience — so read
     * the token on a fresh instance, before redeeming it.
     *
     * <p>Works both for invitations declared with {@link #withBootstrapInvitation(String, String, Map)}
     * (which log the raw {@code Token: …}) and for the {@code admin} environment's built-in
     * {@code first-admin} invitation (which logs a {@code Registration URL: …invitation_token=…}).
     *
     * @param id the bootstrap invitation id (e.g. {@code "first-admin"})
     * @return the raw invitation token, to pass to
     *         {@link com.sympauthy.testcontainers.flow.InteractiveFlow#withInvitationToken(String)}
     * @throws IllegalStateException if no token for {@code id} appears within the timeout
     */
    public String getBootstrapInvitationToken(String id) {
        long deadlineNanos = System.nanoTime() + BOOTSTRAP_TOKEN_TIMEOUT.toNanos();
        while (true) {
            String token = parseBootstrapToken(getLogs(), id);
            if (token != null) {
                return token;
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("No token for bootstrap invitation '" + id
                        + "' found in the container logs after " + BOOTSTRAP_TOKEN_TIMEOUT.toSeconds()
                        + "s. It is logged only after the container is ready and only while no user has "
                        + "yet consented for the invitation's audience — read it on a fresh instance. "
                        + "Declared invitations: " + bootstrapInvitationIds
                        + " (the 'admin' environment also ships 'first-admin').");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for the '" + id + "' bootstrap invitation token", e);
            }
        }
    }

    /**
     * Extracts the token of the bootstrap invitation {@code id} from raw container logs, or returns
     * {@code null} if absent. Handles both log forms SympAuthy emits: {@code … Token: <token>} (no
     * url-template) and {@code … Registration URL: …invitation_token=<token>} (with a url-template).
     */
    static String parseBootstrapToken(String logs, String id) {
        if (logs == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("Bootstrap invitation '" + Pattern.quote(id)
                + "' created[^\\n]*?(?:Token: (\\S+)|invitation_token=([^&\\s]+))");
        Matcher matcher = pattern.matcher(logs);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
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
