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

## Driving the interactive flow

The `com.sympauthy.testcontainers.flow` package walks SympAuthy's
[interactive login flow](https://sympauthy.github.io/functional/interactive_flow.html)
programmatically — from the OAuth authorize endpoint to an authorization code and tokens — so a test
can exercise a full sign-in or sign-up without a browser. `InteractiveFlow` handles PKCE, the flow
state, endpoint discovery, and the server's redirects; you register a callback for each step you care
about.

```java
// A container configured for a password authorization-code flow: password auth, a public client,
// and a flow definition (SympAuthy validates the flow's URLs at startup).
SympauthyContainer sympauthy = new SympauthyContainer().withConfig(Map.of(
    "auth",    Map.of("by-password", Map.of("enabled", true), "identifier-claims", List.of("email")),
    "claims",  Map.of("email", Map.of("enabled", true)),
    "clients", Map.of("test-app", Map.of(
        "public", true,
        "authorizationFlow", "default",
        "allowed-grant-types", List.of("authorization_code"),
        "allowed-scopes", List.of("openid"),
        "allowed-redirect-uris", List.of("http://localhost/callback"))),
    "flows",   Map.of("default", Map.of(
        "type", "web",
        "sign-in", "/sign-in", "sign-up", "/sign-up",
        "collect-claims", "/collect-claims", "validate-claims", "/validate-claims", "error", "/error"))));

try (sympauthy) {
    sympauthy.start();

    TokenResponse tokens = InteractiveFlow.against(sympauthy)
        .withClientId("test-app")
        .withRedirectUri("http://localhost/callback")
        .withScopes("openid")
        .withSignUpHandler(config -> Map.of("email", "ada@example.com", "password", "Str0ngP@ssw0rd!"))
        .withStepListener(step -> System.out.println("reached " + step.type()))  // optional: fires at every step
        .run()        // -> AuthorizationResult (holds the authorization code)
        .exchange();  // -> TokenResponse (access_token, id_token, …)
}
```

Register only the steps a flow needs — each callback is an independent functional interface:

| Callback | Purpose |
| ------------------------------------------- | ------------------------------------------------------------------ |
| `withSignInHandler(SignInHandler)`          | supply credentials for an existing user |
| `withSignUpHandler(SignUpHandler)`          | supply sign-up fields (password + identifier claims) for a new user |
| `withClaimsHandler(ClaimsHandler)`          | supply values when the flow collects extra claims |
| `withStepListener(StepListener)`            | observe every step (logging, assertions) — does not influence the flow |

`run()` returns an `AuthorizationResult` (the authorization code, plus `exchange()` for tokens). For
finer control, `InteractiveFlow.against(sympauthy).api()` exposes a thin `FlowApiClient` with one
method per Flow API endpoint.

> The driver covers the password happy path (configuration → sign-in/sign-up → collect claims →
> code). Multi-factor auth and enforced email/SMS validation are auto-skipped when the server allows
> it and otherwise raise `UnsupportedFlowStepException`.

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
