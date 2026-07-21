# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Testcontainers module that runs [SympAuthy](https://sympauthy.github.io/) — a
self-hosted OAuth 2.1 / OpenID Connect authorization server — from Java tests. Consumers add it as a
library dependency and drive `SympauthyContainer` from their own test suites. The core is one public
class, `src/main/java/com/sympauthy/testcontainers/SympauthyContainer.java`; a `client` package holds
the SympAuthy-server API clients, and a `flow` package adds a programmatic driver for SympAuthy's
interactive login/authorization flow (see "Driving the interactive flow").

### Source layout (`src/main/java`)

The **root package** lists its classes; each sub-package is described at the package level:

```
com.sympauthy.testcontainers          (root)
├─ SympauthyContainer ......... the Testcontainers container that boots & configures SympAuthy
└─ Client ..................... OAuth client credentials (id + optional secret + public/confidential);
                                authenticates a token request (client_secret_basic / client_secret_post)

com.sympauthy.testcontainers.client         HTTP clients for the APIs exposed by the SympAuthy server —
                                            the OAuth2 token endpoint (TokenClient/TokenResponse) and the
                                            Flow API (FlowApiClient/FlowResponse), plus
                                            SympauthyApiException; usable standalone, no flow needed

com.sympauthy.testcontainers.flow           drives SympAuthy's interactive login/authorization flow: the
                                            mock frontend + container wiring, scripted runs, PKCE, and
                                            per-page handlers (builds on the client package's FlowApiClient)

com.sympauthy.testcontainers.internal.json  internal utilities — the JsonCodec wrapper over the
                                            Shadow-relocated minimal-json parser
```

Convention: **internal, non-published utility classes live under `com.sympauthy.testcontainers.internal.*`**
(the namespace the Shadow plugin relocates `minimal-json` into). The dependency direction is one-way:
`flow → client → internal.json`, and `client` depends on nothing in `flow` so the API clients stay usable
standalone.

## Commands

Gradle (Kotlin DSL), Gradle wrapper 9.6.1, Java 17 bytecode target.

```bash
./gradlew test                              # fast, Docker-free unit tests (src/test/java)
./gradlew integrationTest                   # container-starting tests, REQUIRES Docker (src/integrationTest/java)
./gradlew build                             # compile + unit tests + sources/javadoc jars (does NOT run integrationTest)
./gradlew test --tests "SympauthyContainerTest.isMinimalByDefault"   # a single unit test
./gradlew integrationTest --tests "NestedConfigMapIT"                # a single integration test
```

`integrationTest` is a separate source set, not part of `check`/`build` — run it explicitly, and only
with a Docker (or Podman/Colima/etc.) engine available.

## Two source sets, two kinds of test

- **`src/test/java` (unit, no Docker).** These call the Docker-free `configure()` directly and assert
  on the resulting `getCommandParts()` (program arguments) and `getEnv()` (environment). They never
  start a container. Note: `getDockerImageName()` resolves/pulls the image in Testcontainers 2.x, so
  tests assert the default image via the `DEFAULT_IMAGE_NAME`/`DEFAULT_TAG` constants instead of calling it.
- **`src/integrationTest/java` (IT, needs Docker).** Each scenario is a `*IT` subclass of
  `AbstractSympauthyContainerIT`. They boot a real container and assert configuration actually took
  effect by observing the running server, not just that it booted. When adding an IT, follow this
  pattern: subclass, boot, and assert on a signal that is present *only* when the config under test was
  genuinely applied. Pick whatever signal best proves the path you're exercising — the existing
  scenarios watch for the opt-in `email_verified` claim in the discovery document (`EMAIL_CLAIM_MARKER`,
  via the shared `fetchDiscovery` helper), but other cases may inspect different endpoints, response
  fields, or behaviors. Add shared helpers to `AbstractSympauthyContainerIT` as new signals arise.

## Architecture of `SympauthyContainer`

SympAuthy is heavily configuration-driven, so the container avoids a typed method per settings section.
Instead it exposes the full surface through generic escape hatches that map onto the **three Micronaut
configuration mechanisms** the server understands. When adding config capability, fit it into one of
these rather than inventing a new channel:

1. **Program arguments** (`-<key>=<value>`, appended to the entrypoint via `withCommand`) —
   `withProperty` / `withProperties` / `withDatasource`. Backed by a `LinkedHashMap`, so the last
   value per key wins. Best for targeted scalar overrides.
2. **Environment profiles** (`MICRONAUT_ENVIRONMENTS`) — `withEnvironments(...)` *replaces* the set
   (defaults to `default` alone). Profiles: `default`, `by-mail`, `admin`, providers like `google`.
3. **External config files** (`MICRONAUT_CONFIG_FILES`) — `withConfigFile`, `withConfigContent`,
   `withYamlConfig`, `withJsonConfig`, and `withConfig(Map)`. Preserves nested lists/objects (`rules`,
   providers, clients) that flatten badly as indexed program arguments. Later files override earlier ones.

Key invariants to preserve when editing:

- **Everything is deferred to `configure()`.** The `with*` methods only accumulate into fields
  (`properties`, `environments`, `configFilePaths`); `configure()` (called by Testcontainers right
  before start, and directly by unit tests) materializes them into env vars and the command line.
- **The container owns `auth.issuer` and `urls.root`.** `configure()` applies these pins *last* so they
  always beat caller-supplied values — the issuer must stay host-reachable.
- **Host port is pinned up front.** The constructor allocates a free host port (`findFreePort`) and
  `addFixedExposedPort`s it, because the issuer/discovery URL is baked into startup config and must be
  known before the container starts. This is also what lets `getBaseUrl()`/`getIssuerUrl()` be stable
  and callable before `start()`. Multiple instances get distinct ports and can run in parallel.
- **Overall precedence (highest first):** container-managed issuer/root URL > program-argument
  overrides > `MICRONAUT_ENVIRONMENTS` profiles > mounted config files > image's bundled defaults.
- **No JSON dependency in the published API.** `withConfig(Map)` serializes via the in-house
  `toJson`/`appendJson` (Maps → objects, Lists → arrays, Number/Boolean → literals, everything else
  quoted). Don't add a JSON library *for the container* — extend the serializer. The `client`/`flow`
  packages do parse JSON (via `internal.json.JsonCodec`), but its parser (`minimal-json`) is shaded and
  relocated (see "Driving the interactive flow"), so the published jar/POM still expose no JSON dependency.
- **Default image is `ghcr.io/sympauthy/sympauthy-nightly:latest`** (only a nightly is published). The
  constructor `assertCompatibleWith`s this, so a non-nightly image reference is rejected.

### Admin API and bootstrap invitations

Support for creating an admin user (to exercise SympAuthy's Admin API, `/api/v1/admin/*`) is built from
the same escape hatches — no new channel:

- **`withAdmin()`** *adds* the `admin` environment (keeping `default`), unlike `withEnvironments` which
  *replaces*. The `admin` env (SympAuthy's bundled `application-admin.yml`) ships the Admin API/UI, an
  `admin` audience (`sign-up-enabled: false`, `invitation-enabled: true`), the `is_sympauthy_admin`
  boolean claim, a scope-granting `rules.user` (`CLAIM("is_sympauthy_admin") = "true"` → all admin
  scopes), a public `admin` client (its redirect is SympAuthy's own `/admin/callback`, so it is **not**
  reusable by the mock frontend), and a **`first-admin` bootstrap invitation** (audience `admin`, with a
  url-template).
- **`withBootstrapInvitation(id, audience[, claims])`** writes `invitations.<id>.{audience,claims.*}` as
  program arguments (all flat keys — no lists — so `withProperties` fits). It deliberately sets **no**
  `url-template`, so SympAuthy logs the raw `Token: <token>`. Invitations bind to an *audience*, not a
  client. Never declare two invitations for one audience in a single run — `BootstrapInvitationManager`
  revokes the prior one before creating the next.
- **`withAdminClient(registry, scopes…)`** generates an `admin`-audience public client wired to the
  interactive-flow mock frontend (redirect = `registry.redirectUri()`, `authorizationFlow` =
  `registry.flowId()`, allowed/default scopes = `openid` + `scopes`) via `withConfig(Map)` (nested lists
  → config-file channel), and calls `registry.withScopes(...)` so the authorize request and client stay
  in sync. The `admin` env's built-in `admin` client can't be used with the mock frontend, so define a
  dedicated one instead.
- **`getBootstrapInvitationToken(id)`** (call *after* `start()`) polls `getLogs()` for the token. The
  invitation is created on `ServiceReadyEvent` (≈HTTP readiness) and **only while no user has yet
  consented** for its audience — so read it on a fresh instance, before redeeming. Parsing lives in the
  Docker-free static **`parseBootstrapToken(logs, id)`**, which handles *both* log forms: `… Token:
  <token>` (custom, no url-template) and `… Registration URL: …invitation_token=<token>` (the built-in
  `first-admin`).
- **Redemption goes through the interactive flow:** the token rides the authorize request as
  `invitation_token` (see `InteractiveFlow.withInvitationToken` below), the sign-up creates the first
  admin, the invitation pre-sets `is_sympauthy_admin`, the rule grants admin scopes, and the exchanged
  access token is admin-scoped. `AdminApiWithBootstrapInvitationIT` proves the whole path end to end
  (200 on `/api/v1/admin/users` with the token, 401/403 without); `AbstractSympauthyContainerIT.apiGet`
  is the shared bearer-GET helper (works for any authenticated API, not just admin).

## Driving the interactive flow

`com.sympauthy.testcontainers.flow` drives SympAuthy's
[interactive flow](https://sympauthy.github.io/functional/interactive_flow.html) via its
[Flow API](https://sympauthy.github.io/technical/api/flow.html), so a test can go from the authorize
endpoint to an authorization code (and tokens) without a browser.

**`InteractiveFlowRegistry` is a mock of the flow *frontend*, not a client.** It runs a small
`com.sun.net.httpserver.HttpServer` that plays the flow's pages (`/sign-in`, `/sign-up`,
`/collect-claims`, `/validate-claims`, `/error`) plus the client's `/callback`. One registry hosts one
`flows.<id>` definition and one client but any number of `InteractiveFlow`s — each a single scripted
run (a sign-up, a sign-in, …) minted with `registry.newFlow()`; the registry serves whichever flow's
`run()` is currently executing. SympAuthy owns the orchestration — it decides, via the `redirect_url`
each Flow API call returns, which page comes next — while each mock page just calls the running flow's
callback and submits to the Flow API. A redirect-**following** HTTP client ("browser") rides
SympAuthy's 303s across the pages until `/callback` captures the code.

Lifecycle (the flow's page URLs must be in SympAuthy's startup config, so the server binds first):
`InteractiveFlowRegistry.forClient(id)` (binds a local port) → `registry.newFlow().with*Handler(...)`
→ `container.withFlows(registry)` (applies the `flows.<id>` definition and calls
`registry.attach(baseUrl, discoveryUrl)`; you supply the matching client) → `container.start()` →
`flow.run()` → `AuthorizationResult` → `exchange()` → `TokenResponse`. Register a flow per run and run
them in order — e.g. sign up, then sign in as that user (each `run()` uses a fresh browser session).
The authorize/token endpoints are read from the **discovery document**, not hardcoded.

Key points when extending:

- **`SympauthyContainer.withFlows(InteractiveFlowRegistry)`** is the one place the core container
  depends on the flow package (chosen for ergonomics). It contributes **only** the `flows.<id>`
  definition — applied via `withProperties` (program-argument overrides) from
  `registry.flowProperties()`, so it wins over caller-supplied flow config without erasing it. The
  **client is the caller's** to define (`clients.<id>` with id `registry.clientId()`,
  `authorizationFlow` `registry.flowId()`, and `allowed-redirect-uris` including
  `registry.redirectUri()`), along with the auth method and claims.
- **Registry vs. flow split:** the `InteractiveFlowRegistry` owns the shared, container-facing state —
  the HTTP server/port, `clientId`/`flowId`/`scopes`, `attach`/`flowProperties`, and the dispatch
  routing; `InteractiveFlow` (same package, so the registry reads its package-private handler fields)
  carries only the per-run callbacks and a `run()` that delegates to `registry.run(this)`.
- **`FlowApiClient`** (in the **`client` package**) — the thin client each mock page uses (one method
  per Flow API endpoint, returning a parsed `client.FlowResponse`). It encapsulates the state transport:
  **GET carries the state as `?state=<jwt>`, POST carries it in an `Authorization: State <jwt>` header.**
  Public, for custom flows. It lives in `client` alongside `TokenClient` because both are HTTP clients
  for SympAuthy-server APIs; both throw `client.SympauthyApiException` on failure.
- **Callbacks are segregated single-method interfaces** (`SignInHandler`, `SignUpHandler`,
  `ClaimsHandler`, `ValidationCodeHandler`, `StepListener`), registered per `InteractiveFlow` with
  `with*`. A page reached without its handler throws; `StepListener` observes *every* page. Do not
  collapse the split.
- **`InteractiveFlow.withInvitationToken(token)`** is a per-run field (only the redeeming sign-up needs
  it): the registry adds it as the `invitation_token` query parameter in `authorizeParams(...)`, so the
  authorize request redeems a (bootstrap) invitation. Pair it with a sign-up handler — e.g. the first
  admin from `SympauthyContainer.getBootstrapInvitationToken("first-admin")` (see "Admin API and
  bootstrap invitations" above).
- **`InteractiveFlow.withNonce(nonce)`** is the same per-run pattern: the registry adds it as the
  `nonce` query parameter in `authorizeParams(...)` (only when set), so the OIDC `nonce` rides the
  authorize request to be echoed back in the `id_token` (OpenID Connect Core §3.1.3.7 / §15.5.2 replay
  mitigation). The module only *sends* it, verified Docker-free in `InteractiveFlowTest`; **echoing it
  is a server capability**, so the end-to-end "`id_token` carries the nonce" check lives in SympAuthy's
  own integration tests, not here.
- **Traversed steps are accumulated on the flow.** The registry's `emit(...)` appends each `FlowStep`
  to the running flow (a package-private `CopyOnWriteArrayList`, reset at the start of each `run()`)
  *before* notifying the listener, and `InteractiveFlow.stepTypes()` exposes the `List<FlowStep.Type>`
  traversed. That is the boilerplate-free way to assert on the path taken; `StepListener` stays for
  reacting to a step as it happens (reading its `data()`, calling the Flow API mid-flow).
- **v1 covers the password happy path** (sign-in/sign-up → collect claims → code). The
  `/validate-claims` page throws `UnsupportedFlowStepException` (the seam for a future validation
  tier); MFA is not modelled.
- **PKCE `S256` is always sent.** By default the registry drives a **public** client (no secret), so
  token exchange uses the verifier only (`InteractiveFlowRegistry.forClient`). For a **confidential**
  client, use `forConfidentialClient(clientId, secret)`: the exchange also authenticates the client,
  sending the secret as `client_secret_post` (default) or `client_secret_basic` (opt in with
  `withClientAuthMethod(Client.ClientAuthMethod.BASIC)`), while still sending PKCE. Declare a matching
  `clients.<id>.secret` (the config key is **`secret`**, and `public` defaults to `false`);
  `registry.clientSecret()` exposes the value so the sent and configured secrets stay in sync. The
  client identity is a **`Client`** (root package: id + optional secret + public/confidential, with
  `authenticate(form, request)`); the exchange runs through **`client.TokenClient`**, whose
  `clientCredentials(scopes…)` grant is reusable to obtain a token for SympAuthy's Client API with no
  interactive flow (`registry.client()` hands you the `Client` to build one). The module only *sends*
  the secret (verified Docker-free in `TokenClientTest`/`InteractiveFlowTest`); the end-to-end
  confidential exchange / revoke / introspect proof lives in SympAuthy's own integration tests.
- **`JsonCodec` is the only class that touches the JSON parser.** It wraps `minimal-json` and lives in
  `com.sympauthy.testcontainers.internal.json` (public, shared by `flow` and `client`) — the same
  package the Shadow plugin relocates `minimal-json` into. Internal, non-published utilities live under
  `com.sympauthy.testcontainers.internal.*`; swapping parsers stays a one-file change.
- **Unit tests are Docker-free**: the interactive-flow tests (`src/test/java/.../flow/`) run the mock
  frontend against a stub SympAuthy (the in-JVM `TestFlowServer`) whose `/authorize` redirects to the
  frontend's page URLs and whose Flow API returns scripted `redirect_url`s (`InteractiveFlowTest`, which
  also covers the confidential-client exchange). `TokenClientTest` (`.../client/`, with its own small
  recording server) and `ClientTest` (root package) cover the token client and credential authentication
  directly. `SignUpWithInteractiveFlowIT` and `SignInWithInteractiveFlowIT` boot a real container and
  wire the frontend with `withFlows`.
- **How the redirects flow (verified):** with the flow's page URLs pointing at the mock frontend,
  `/authorize` 303-redirects the browser to `<frontend>/sign-in?state=<jwt>`; each page's Flow API call
  returns the next `redirect_url` (another frontend page, or the client `/callback?code=`). The browser
  hits SympAuthy only at `/authorize`; the frontend pages call the Flow API server-side on the
  container's base URL. SympAuthy's flow keys are **flat** and validated at startup
  (`flows.<id>.{type, sign-in, sign-up, collect-claims, validate-claims, error}` must all be present,
  else `/authorize` returns HTTP 500 `config.invalid` — the container still boots, the readiness
  printer only logs the errors).

## Dependency versioning

Dependencies live in the Gradle version catalog at `gradle/libs.versions.toml`; `build.gradle.kts`
references them through type-safe `libs.*` accessors. The catalog deliberately keeps **two**
Testcontainers versions: `testcontainers-min` (`2.0.0`), which the `api` dependency
(`libs.testcontainers`) is pinned to, and `testcontainers` (`2.0.5`), which the test-only
`libs.testcontainers.junit.jupiter` uses and which we build/test against. Declaring the `api`
dependency against the **lowest** supported release widens compatibility: Gradle resolves to the
highest requested version, so consumers already on 2.0.0+ aren't forced to upgrade. The module is
Testcontainers **2.x only** (2.0 relocated packages and dropped JUnit 4).

The JSON parser (`minimal-json`, wrapped by `internal.json.JsonCodec`) is **bundled and relocated**, not
exposed: it lives in a dedicated `shade` configuration (extended into `compileOnly`/`testImplementation`
so main and tests compile against the un-relocated classes) and the `com.gradleup.shadow` plugin's
`shadowJar` relocates it into `com.sympauthy.testcontainers.internal.json` (our `JsonCodec` wrapper
already lives in that package and is left untouched by the relocation). The published artifact is that shadow
jar, and the POM is hand-built (`pom.withXml`) to list **only** `testcontainers` — so a consumer
inherits no JSON dependency and cannot hit a version clash. Prefer this shade-and-relocate approach
over exposing a third-party dependency when adding one. Verify after changes: `jar tf` on the built
jar shows classes under `internal/json/` (not the original package), and
`generatePomFileForMavenPublication` shows only Testcontainers. `nimbus-jose-jwt` is a **test-only**
dependency (parses id_tokens in the interactive-flow ITs); it is neither shaded nor published.
