# testcontainers-sympauthy
Testcontainers module for SympAuthy, an OAuth 2.0 / OpenID Connect authorization server

## Installation

The library is published to **GitHub Packages** at
`https://maven.pkg.github.com/sympauthy/testcontainers-sympauthy`. Even though the package is public,
GitHub's Maven registry requires authentication for every read, so you need a
[personal access token](https://github.com/settings/tokens) with the `read:packages` scope (classic
token) — supply it as the password below. It's a test-only dependency.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/sympauthy/testcontainers-sympauthy")
        credentials {
            // Set gpr.user / gpr.token in ~/.gradle/gradle.properties, or fall back to env vars.
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    testImplementation("com.sympauthy:testcontainers-sympauthy:x.x.x")
}
```

### Maven

Add a server with your token to `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github-sympauthy</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_GITHUB_TOKEN</password> <!-- PAT with read:packages -->
  </server>
</servers>
```

Then reference the repository and dependency in your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-sympauthy</id>
    <url>https://maven.pkg.github.com/sympauthy/testcontainers-sympauthy</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.sympauthy</groupId>
    <artifactId>testcontainers-sympauthy</artifactId>
    <version>x.x.x</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

The only dependency you inherit is Testcontainers itself (the JSON parser used internally is shaded).

### In CI (GitHub Actions)

No personal access token needed — the workflow's automatic `GITHUB_TOKEN` can read the (public)
package. Grant it `packages: read` and pass it through as the registry password:

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: read          # lets GITHUB_TOKEN read GitHub Packages
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew test
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}   # consumed by the credentials block above
```

This reuses the `System.getenv("GITHUB_ACTOR")` / `System.getenv("GITHUB_TOKEN")` fallback in the
Gradle snippet, so the same build works locally (via `gpr.*` properties) and in CI (via these env
vars). For Maven, store the token as a secret and reference it from the `<server>` in `settings.xml`.

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

The `com.sympauthy.testcontainers.flow` package drives SympAuthy's
[interactive login flow](https://sympauthy.github.io/functional/interactive_flow.html)
programmatically — from the OAuth authorize endpoint to an authorization code and tokens — without a
browser. `InteractiveFlowRegistry` is a **mock of the flow frontend**: it stands up a small local HTTP
server that plays the flow's pages (sign-in, collect-claims, …) plus the client's callback. One
registry hosts one `flows.<id>` definition and one client, but any number of `InteractiveFlow`s — each
a single scripted run (a sign-up, a sign-in, …) minted with `registry.newFlow()`. SympAuthy still owns
the orchestration — it decides, through the redirects it issues, which page comes next — while your
callbacks "render" each page by submitting to the Flow API.

Create the registry first (it binds a local port immediately), hand it to the container with
`withFlows`, then mint a flow, start, and `run()`:

```java
try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("test-app"))
        .withScopes("openid");
     SympauthyContainer sympauthy = new SympauthyContainer()
        .withConfig(Map.of(
            "auth",    Map.of("by-password", Map.of("enabled", true), "identifier-claims", List.of("email")),
            "claims",  Map.of("email", Map.of("enabled", true)),
            // You own the client — point it at the frontend's callback and flow id:
            "clients", Map.of("test-app", Map.of(
                "public", true,
                "authorizationFlow", registry.flowId(),
                "allowed-grant-types", List.of("authorization_code"),
                "allowed-scopes", List.of("openid"),
                "allowed-redirect-uris", List.of(registry.redirectUri())))))
        .withFlows(registry)) {   // contributes only the flows.<id> definition

    InteractiveFlow signUp = registry.newFlow()
        .withSignUpHandler(config -> Map.of("email", "ada@example.com", "password", "Str0ngP@ssw0rd!"))
        .withStepListener(step -> System.out.println("reached " + step.type()));  // optional, react live

    sympauthy.start();

    TokenResponse tokens = signUp.run()   // -> AuthorizationResult (holds the authorization code)
        .exchange();                      // -> TokenResponse (access_token, id_token, …)

    // Assert on the path taken, no listener needed:
    assertEquals(List.of(SIGN_UP, COMPLETED), signUp.stepTypes());
}
```

`withFlows(registry)` contributes only the `flows.<id>` definition (the mock frontend's pages), applied
as program-argument overrides so it wins over any flow config you set elsewhere, and tells the frontend
the container's URLs. **You own the client:** give it `registry.clientId()`, set its `authorizationFlow`
to `registry.flowId()`, and include `registry.redirectUri()` in its redirect URIs. Then `/authorize`
redirects a redirect-following client through the mock frontend's pages to its `/callback`, which
captures the code. Register only the pages a flow reaches — each callback is an independent functional
interface:

| Callback | Purpose |
| ------------------------------------------- | ------------------------------------------------------------------ |
| `withSignInHandler(SignInHandler)`          | supply credentials for an existing user |
| `withSignUpHandler(SignUpHandler)`          | supply sign-up fields (password + identifier claims) for a new user |
| `withClaimsHandler(ClaimsHandler)`          | supply values when the collect-claims page is reached |
| `withStepListener(StepListener)`            | react to every page as the flow reaches it (read its `data()`, call the Flow API) — does not influence it |

`run()` returns an `AuthorizationResult` (the authorization code, plus `exchange()` for tokens). To
assert on the path a flow took, read `flow.stepTypes()` — the `List<FlowStep.Type>` it traversed (e.g.
`[SIGN_UP, COMPLETED]`) — instead of accumulating them through a `StepListener`. For lower-level
access, `FlowApiClient` wraps each Flow API endpoint directly.

Register a flow per run and run them in order to chain scenarios against one container — for example a
sign-up that creates a user, then a sign-in as that same user (both share the one client and flow):

```java
InteractiveFlow signUp = registry.newFlow().withSignUpHandler(cfg -> Map.of("email", email, "password", password));
InteractiveFlow signIn = registry.newFlow().withSignInHandler(cfg -> Credentials.of(email, password));
sympauthy.start();
signUp.run().exchange();                          // creates the user
TokenResponse tokens = signIn.run().exchange();   // signs in as that user
```

> The frontend covers the password happy path (sign-in/sign-up → collect claims → code). Multi-factor
> auth and enforced email/SMS validation raise `UnsupportedFlowStepException`.

### Confidential clients

Pass a confidential `Client` to authenticate at the token endpoint with a secret (`client_secret_post`,
or `client_secret_basic`). Declare the matching client with `"public", false` and `"secret",
registry.clientSecret()`.

```java
InteractiveFlowRegistry registry = InteractiveFlowRegistry
    .forClient(Client.confidentialClient("test-app", "s3cr3t"))  // + Client.ClientAuthMethod.BASIC for HTTP Basic
    .withScopes("openid");
```

## Creating an admin user (Admin API)

SympAuthy's [Admin API](https://sympauthy.github.io/technical/api/admin.html) (`/api/v1/admin/*`)
unlocks advanced test scenarios — create/list/disable users, manage invitations, inspect config. It is
reached with an access token carrying admin scopes, and the *first* admin user can only be created by
redeeming a [**bootstrap invitation**](https://sympauthy.github.io/functional/invitation.html#bootstrap-invitations):
a token SympAuthy generates and logs at startup.

`withAdmin()` enables the `admin` environment, which ships everything needed except a flow-driveable
client: the Admin API/UI, an `admin` audience, the `is_sympauthy_admin` claim, a scope-granting rule,
and a `first-admin` bootstrap invitation. `withAdminClient(registry, scopes…)` adds a public client
bound to the `admin` audience and wired to the interactive-flow mock frontend (and requests those same
scopes). Read the invitation token from the logs, redeem it through a sign-up flow with
`withInvitationToken(...)`, and use the resulting access token against the Admin API:

```java
try (InteractiveFlowRegistry registry = InteractiveFlowRegistry.forClient(Client.publicClient("admin-app"))
        .withFlowId("admin-flow");
     SympauthyContainer sympauthy = new SympauthyContainer()
        .withAdmin()                                    // Admin API + audience + claim + rule + first-admin invitation
        .withAdminClient(registry, "admin:users:read")  // public admin-audience client wired to the frontend
        .withConfig(Map.of(                             // password auth so the invited admin can sign up
            "auth",   Map.of("by-password", Map.of("enabled", true), "identifier-claims", List.of("email")),
            "claims", Map.of("email", Map.of("enabled", true))))
        .withFlows(registry)) {

    sympauthy.start();

    // 1. Read the bootstrap invitation token SympAuthy logged at startup.
    String token = sympauthy.getBootstrapInvitationToken("first-admin");

    // 2. Redeem it: signing up with the token creates the first admin user.
    TokenResponse admin = registry.newFlow()
        .withInvitationToken(token)
        .withSignUpHandler(cfg -> Map.of("email", "admin@example.com", "password", "Str0ngP@ssw0rd!"))
        .run()
        .exchange();

    // 3. Call the Admin API with the admin-scoped access token.
    HttpResponse<String> users = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(sympauthy.getBaseUrl() + "/api/v1/admin/users"))
            .header("Authorization", "Bearer " + admin.accessToken())
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, users.statusCode());
}
```

`getBootstrapInvitationToken(id)` returns the raw token for either log form (a raw `Token:` from
`withBootstrapInvitation`, or the `Registration URL:` the built-in `first-admin` logs). Declare your own
invitation for other audiences with
`withBootstrapInvitation("my-invite", "my-audience", Map.of("some_claim", "value"))`. The token is only
logged while no user has yet consented for the audience, so read it on a fresh container before
redeeming.

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
