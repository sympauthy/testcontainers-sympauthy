# testcontainers-sympauthy
Testcontainers module for SympAuthy, an OAuth 2.0 / OpenID Connect authorization server

## Usage

```java
try (SympauthyContainer sympauthy = new SympauthyContainer()) {
    sympauthy.start();

    String issuer    = sympauthy.getIssuerUrl();               // http://localhost:<port>
    String discovery = sympauthy.getOpenIdConfigurationUrl();  // .../.well-known/openid-configuration
    // point the system under test at the issuer / discovery document
}
```

Out of the box the container is **minimal**: it runs SympAuthy's `default` Micronaut environment
against an in-memory H2 database and pins the issuer to a host-reachable `http://localhost:<port>`.
Password authentication, claims, the `admin` environment, mail, clients, providers, etc. are all
**opt-in** — enable exactly what a test needs with the methods below.

## Configuration

SympAuthy is heavily configuration-driven. The container exposes its full surface through a few
generic escape hatches mapping onto the three mechanisms the server understands.

### Property overrides (Micronaut program arguments)

For targeted scalar overrides:

```java
new SympauthyContainer()
    .withProperty("auth.by-password.enabled", "true")
    .withProperties(Map.of("claims.email.enabled", "true"));
```

### Environment profiles (`MICRONAUT_ENVIRONMENTS`)

```java
new SympauthyContainer()
    .withEnvironments("default", "admin"); // replaces the set; here: baseline + Admin API/UI
```

Common profiles: `default` (baseline), `by-mail` (email/password auth), `admin` (Admin API + UI and
a pre-provisioned admin client), and well-known providers such as `google`. Include `default`
whenever you replace the set — it supplies the baseline configuration.

### Bulk config files (YAML / JSON, via `MICRONAUT_CONFIG_FILES`)

```java
new SympauthyContainer()
    // a nested map, serialized to a JSON file — lists and nested objects (e.g. `rules`,
    // providers, clients) are preserved, unlike flattened program arguments:
    .withConfig(Map.of(
        "auth", Map.of(
            "by-password", Map.of("enabled", true),
            "identifier-claims", List.of("email")),
        "claims", Map.of("email", Map.of("enabled", true))))
    .withConfigFile(MountableFile.forClasspathResource("sympauthy.yml")) // mount an existing file
    .withYamlConfig("""
        auth:
          by-password:
            enabled: true
        """)                                                             // or inline content
    .withJsonConfig("{\"claims\":{\"email\":{\"enabled\":true}}}");
```

### Datasource

```java
new SympauthyContainer()
    .withDatasource("r2dbc:postgresql://db:5432/sympauthy", "user", "pass");
```

The referenced database must be reachable from inside the container — for a companion PostgreSQL
container, put both on the same `Network` and use its network alias as the host.

### Precedence

Highest wins: the container-managed issuer / root URL &gt; property overrides
(`withProperty` / `withProperties`) &gt; `MICRONAUT_ENVIRONMENTS` profiles &gt; mounted config files
(`withConfig` / `withConfigFile` / `withConfigContent`, later files override earlier ones) &gt; the
image's bundled defaults. `auth.issuer` and `urls.root` are always pinned by the container
so the issuer stays reachable from host-side test code.

## Requirements

| Requirement       | Minimum version                                                                        |
| ----------------- | -------------------------------------------------------------------------------------- |
| Java (JDK)        | 17 or newer                                                                            |
| Testcontainers    | 2.0.0 or newer (2.x line)                                                              |
| JUnit             | 5 (Jupiter) — optional; the container can also be driven with a manual lifecycle       |
| Container runtime | Docker, or a Testcontainers-supported alternative (Podman, Colima, Rancher Desktop, …) |

- **Java 17** is the module's compilation target — the lowest JVM supported by the Testcontainers
  2.x line, chosen for the widest compatibility on that line.
- **Testcontainers 2.x** is required: the 2.0 release relocated packages
  (`org.testcontainers.containers.*` → `org.testcontainers.<module>.*`) and dropped JUnit 4, so the
  module is not compatible with the 1.x line.
- A running **Docker** (or compatible) engine must be available on the machine executing the tests.
