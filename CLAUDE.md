# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Testcontainers module that runs [SympAuthy](https://sympauthy.github.io/) — a
self-hosted OAuth 2.1 / OpenID Connect authorization server — from Java tests. Consumers add it as a
library dependency and drive `SympauthyContainer` from their own test suites. The core is one public
class, `src/main/java/com/sympauthy/testcontainers/SympauthyContainer.java`; a second package,
`src/main/java/com/sympauthy/testcontainers/flow/`, adds a programmatic driver for SympAuthy's
interactive login/authorization flow (see "Driving the interactive flow").

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
  quoted). Don't add a JSON library *for the container* — extend the serializer. The `flow` package
  does parse JSON, but its parser (`minimal-json`) is shaded and relocated (see "Driving the
  interactive flow"), so the published jar/POM still expose no JSON dependency.
- **Default image is `ghcr.io/sympauthy/sympauthy-nightly:latest`** (only a nightly is published). The
  constructor `assertCompatibleWith`s this, so a non-nightly image reference is rejected.

## Driving the interactive flow

`com.sympauthy.testcontainers.flow` drives SympAuthy's
[interactive flow](https://sympauthy.github.io/functional/interactive_flow.html) via its
[Flow API](https://sympauthy.github.io/technical/api/flow.html), so a test can go from the authorize
endpoint to an authorization code (and tokens) without a browser.

**`InteractiveFlow` is a mock of the flow *frontend*, not a client.** It runs a small
`com.sun.net.httpserver.HttpServer` that plays the flow's pages (`/sign-in`, `/collect-claims`,
`/validate-claims`, `/error`) plus the client's `/callback`. SympAuthy owns the orchestration — it
decides, via the `redirect_url` each Flow API call returns, which page comes next — while each mock
page just calls the registered callback and submits to the Flow API. A redirect-**following** HTTP
client ("browser") rides SympAuthy's 303s across the pages until `/callback` captures the code.

Lifecycle (the flow's page URLs must be in SympAuthy's startup config, so the server binds first):
`InteractiveFlow.forClient(id)` (binds a local port) → `container.withFlow(flow)` (merges the flow's
`clients.<id>` + `flows.<id>` config and calls `flow.attach(baseUrl, discoveryUrl)`) →
`container.start()` → `flow.run()` → `AuthorizationResult` → `exchange()` → `TokenResponse`. The
authorize/token endpoints are read from the **discovery document**, not hardcoded.

Key points when extending:

- **`SympauthyContainer.withFlow(InteractiveFlow)`** is the one place the core container depends on the
  flow package (chosen for ergonomics). It only contributes the client + flow definition; the caller
  still configures the auth method and claims separately.
- **`FlowApiClient`** — the thin client each mock page uses (one method per Flow API endpoint,
  returning a parsed `FlowResponse`). It encapsulates the state transport: **GET carries the state as
  `?state=<jwt>`, POST carries it in an `Authorization: State <jwt>` header.** Public, for custom flows.
- **Callbacks are segregated single-method interfaces** (`SignInHandler`, `SignUpHandler`,
  `ClaimsHandler`, `ValidationCodeHandler`, `StepListener`), registered with `with*`. A page reached
  without its handler throws; `StepListener` observes *every* page. Do not collapse the split.
- **v1 covers the password happy path** (sign-in/sign-up → collect claims → code). The
  `/validate-claims` page throws `UnsupportedFlowStepException` (the seam for a future validation
  tier); MFA is not modelled.
- **PKCE `S256` is always sent**; `withFlow` generates a **public** client, so token exchange uses the
  verifier only (no secret).
- **`JsonCodec` is the only class that touches the JSON parser.** It wraps `minimal-json`, which the
  Shadow plugin relocates into `com.sympauthy.testcontainers.internal.json` — swapping parsers is a
  one-file change.
- **Unit tests are Docker-free** (`src/test/java/.../flow/`): the mock frontend runs against a stub
  SympAuthy (the in-JVM `TestFlowServer`) whose `/authorize` redirects to the frontend's page URLs and
  whose Flow API returns scripted `redirect_url`s. `InteractiveFlowIT` boots a real container and wires
  the frontend with `withFlow`.
- **How the redirects flow (verified):** with the flow's page URLs pointing at the mock frontend,
  `/authorize` 303-redirects the browser to `<frontend>/sign-in?state=<jwt>`; each page's Flow API call
  returns the next `redirect_url` (another frontend page, or the client `/callback?code=`). The browser
  hits SympAuthy only at `/authorize`; the frontend pages call the Flow API server-side on the
  container's base URL. SympAuthy's flow keys are **flat** and validated at startup
  (`flows.<id>.{type, sign-in, collect-claims, validate-claims, error}` must all be present, else
  `/authorize` returns HTTP 500 `config.invalid` — the container still boots, the readiness printer
  only logs the errors).

## Dependency versioning

Dependencies live in the Gradle version catalog at `gradle/libs.versions.toml`; `build.gradle.kts`
references them through type-safe `libs.*` accessors. The catalog deliberately keeps **two**
Testcontainers versions: `testcontainers-min` (`2.0.0`), which the `api` dependency
(`libs.testcontainers`) is pinned to, and `testcontainers` (`2.0.5`), which the test-only
`libs.testcontainers.junit.jupiter` uses and which we build/test against. Declaring the `api`
dependency against the **lowest** supported release widens compatibility: Gradle resolves to the
highest requested version, so consumers already on 2.0.0+ aren't forced to upgrade. The module is
Testcontainers **2.x only** (2.0 relocated packages and dropped JUnit 4).

The flow package's JSON parser (`minimal-json`) is **bundled and relocated**, not exposed: it lives
in a dedicated `shade` configuration (extended into `compileOnly`/`testImplementation` so main and
tests compile against the un-relocated classes) and the `com.gradleup.shadow` plugin's `shadowJar`
relocates it into `com.sympauthy.testcontainers.internal.json`. The published artifact is that shadow
jar, and the POM is hand-built (`pom.withXml`) to list **only** `testcontainers` — so a consumer
inherits no JSON dependency and cannot hit a version clash. Prefer this shade-and-relocate approach
over exposing a third-party dependency when adding one. Verify after changes: `jar tf` on the built
jar shows classes under `internal/json/` (not the original package), and
`generatePomFileForMavenPublication` shows only Testcontainers. `nimbus-jose-jwt` is a **test-only**
dependency (parses id_tokens in `InteractiveFlowIT`); it is neither shaded nor published.
