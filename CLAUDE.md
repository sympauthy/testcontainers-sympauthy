# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this is

A single-module Testcontainers module that runs [SympAuthy](https://sympauthy.github.io/) — a
self-hosted OAuth 2.1 / OpenID Connect authorization server — from Java tests. Consumers add it as a
library dependency and drive `SympauthyContainer` from their own test suites.

**Usage is documented for users** at <https://sympauthy.github.io/testcontainers/> (per-topic links in
the README): getting started, configuration, interactive flow, clients, MFA, invitations, Admin API,
server-initiated flows. This file covers only what neither that site nor the code states: layout,
commands, and the invariants to preserve when editing.

### Source layout (`src/main/java`)

```
com.sympauthy.testcontainers      SympauthyContainer (boots & configures the server) and Client
                                  (OAuth client credentials: id + optional secret, public or
                                  confidential; authenticates a token request)
  .client                         HTTP clients for the server's APIs — the OAuth2 token endpoint
                                  (TokenClient/TokenResponse) and the Flow API (FlowApiClient/
                                  FlowResponse), plus SympauthyApiException. Usable standalone.
  .flow                           browser-free driver for SympAuthy's interactive login/authorization
                                  flow: mock frontend + container wiring, scripted runs, PKCE, TOTP
                                  (RFC 6238), per-page handlers, link-driven runs
  .internal.json                  JsonCodec, the wrapper over the relocated minimal-json parser
```

Dependency direction is one-way — `flow → client → internal.json` — and `client` never depends on
`flow`, so the API clients stay usable without the driver. Internal, non-published utilities go under
`com.sympauthy.testcontainers.internal.*`, the namespace Shadow relocates `minimal-json` into.

## Commands

Gradle (Kotlin DSL), wrapper 9.6.1, Java 17 bytecode target.

```bash
./gradlew test                              # fast, Docker-free unit tests (src/test/java)
./gradlew integrationTest                   # container-starting tests, REQUIRES Docker (src/integrationTest/java)
./gradlew build                             # compile + unit tests + sources/javadoc jars (NOT integrationTest)
./gradlew test --tests "SympauthyContainerTest.isMinimalByDefault"   # a single unit test
./gradlew integrationTest --tests "NestedConfigMapIT"                # a single integration test
```

`integrationTest` is a separate source set, not part of `check`/`build` — run it explicitly, and only
with a Docker (or Podman/Colima/…) engine available.

## Two source sets, two kinds of test

- **`src/test/java` (unit, no Docker)** calls the Docker-free `configure()` directly and asserts on the
  resulting `getCommandParts()` (program arguments) and `getEnv()`. Never starts a container. Note:
  `getDockerImageName()` resolves/pulls the image in Testcontainers 2.x, so assert the default image
  via the `DEFAULT_IMAGE_NAME`/`DEFAULT_TAG` constants instead of calling it.
- **`src/integrationTest/java` (IT, needs Docker)** — each scenario is a `*IT` subclass of
  `AbstractSympauthyContainerIT`. Boot a real container and assert on a signal present *only* when the
  config under test was genuinely applied, not merely that it booted. Existing scenarios watch for the
  opt-in `email_verified` claim in the discovery document (`EMAIL_CLAIM_MARKER`, via the shared
  `fetchDiscovery` helper); pick whatever signal best proves your path, and add new shared helpers to
  `AbstractSympauthyContainerIT`.
- **Flow unit tests are Docker-free too**: the mock frontend runs against the in-JVM stub
  `TestFlowServer`, whose `/authorize` redirects to the frontend's pages and whose Flow API returns
  scripted `redirect_url`s. It records each request's **raw** query, so a test can assert on what went
  over the wire (`RecordedRequest.queryParam` decodes one value back).

## Architecture of `SympauthyContainer`

SympAuthy is heavily configuration-driven, so the container exposes generic escape hatches instead of a
typed method per settings section. They map onto the **three Micronaut configuration mechanisms** —
fit new config capability into one of these rather than inventing a channel:

1. **Program arguments** (`-<key>=<value>`, appended via `withCommand`) — `withProperty` /
   `withProperties` / `withDatasource`. Backed by a `LinkedHashMap`, last value per key wins. Best for
   targeted scalar overrides.
2. **Environment profiles** (`MICRONAUT_ENVIRONMENTS`) — `withEnvironments(...)` *replaces* the set
   (default: `default` alone). Profiles: `default`, `by-mail`, `admin`, providers like `google`.
3. **External config files** (`MICRONAUT_CONFIG_FILES`) — `withConfigFile`, `withConfigContent`,
   `withYamlConfig`, `withJsonConfig`, `withConfig(Map)`. Preserves nested lists/objects (`rules`,
   providers, clients) that flatten badly as indexed program arguments. Later files override earlier.

Invariants to preserve:

- **Everything is deferred to `configure()`.** The `with*` methods only accumulate into fields;
  `configure()` (called by Testcontainers right before start, and directly by unit tests) materializes
  them into env vars and the command line.
- **The container owns `auth.issuer` and `urls.root`** — `configure()` pins these *last* so they beat
  caller-supplied values; the issuer must stay host-reachable.
- **Host port is pinned up front.** The constructor allocates a free port and `addFixedExposedPort`s
  it, because the issuer/discovery URL is baked into startup config. That is also what makes
  `getBaseUrl()`/`getIssuerUrl()` stable and callable before `start()`, and lets instances run in
  parallel on distinct ports.
- **Precedence (highest first):** container-managed issuer/root URL > program arguments >
  `MICRONAUT_ENVIRONMENTS` profiles > mounted config files > the image's bundled defaults.
- **No JSON dependency in the published API.** `withConfig(Map)` serializes via the in-house
  `toJson`/`appendJson` — extend the serializer rather than adding a JSON library *for the container*.
  (`client`/`flow` do parse JSON, but via the shaded parser; see "Dependency versioning".)
- **Default image is `ghcr.io/sympauthy/sympauthy-nightly:latest`** (only a nightly is published); the
  constructor `assertCompatibleWith`s it, so a non-nightly reference is rejected.

### Admin API and bootstrap invitations

Built from the same escape hatches — no new channel:

- **`withAdmin()`** *adds* the `admin` environment (keeping `default`), unlike `withEnvironments`, which
  replaces. That env ships the Admin API/UI, an `admin` audience, the `is_sympauthy_admin` claim, a
  scope-granting rule, a `first-admin` bootstrap invitation, and a public `admin` client whose redirect
  is SympAuthy's own `/admin/callback` — so it is **not** usable with the mock frontend. Use
  **`withAdminClient(registry, scopes…)`** instead: it generates an `admin`-audience public client wired
  to the frontend via `withConfig(Map)` and calls `registry.withScopes(...)` to keep client and
  authorize request in sync.
- **`withBootstrapInvitation(id, audience[, claims])`** writes flat `invitations.<id>.*` program
  arguments and deliberately sets **no** `url-template`, so SympAuthy logs the raw `Token: <token>`.
  Invitations bind to an *audience*, not a client — never declare two for one audience in a single run
  (`BootstrapInvitationManager` revokes the prior one).
- **`getBootstrapInvitationToken(id)`** (after `start()`) polls `getLogs()`. The invitation is created
  on `ServiceReadyEvent` and only while no user has consented for its audience, so read it on a fresh
  instance before redeeming. Parsing lives in the Docker-free static `parseBootstrapToken(logs, id)`,
  which handles both log forms (`Token: <token>` and `Registration URL: …invitation_token=<token>`).
- **Redemption goes through the interactive flow** (`InteractiveFlow.withInvitationToken`);
  `AdminApiWithBootstrapInvitationIT` proves the path end to end, and
  `AbstractSympauthyContainerIT.apiGet` is the shared bearer-GET helper for any authenticated API.

## Driving the interactive flow

The `.flow` package drives SympAuthy's [interactive flow](https://sympauthy.github.io/functional/interactive_flow.html)
via its [Flow API](https://sympauthy.github.io/technical/api/flow.html), so a test reaches an
authorization code (and tokens) without a browser.

**`InteractiveFlowRegistry` mocks the flow *frontend*, not a client.** It runs a
`com.sun.net.httpserver.HttpServer` serving the flow's pages (`/sign-in`, `/sign-up`, `/confirm`,
`/collect-claims`, `/validate-claims`, the four `mfa-*` pages, `/error`) plus the client's `/callback`.
One registry hosts one `flows.<id>` definition and one client, but any number of `InteractiveFlow`s —
each a single scripted run minted with `registry.newFlow()`. SympAuthy owns the orchestration: each
Flow API response's `redirect_url` decides the next page, and a redirect-following HTTP client
("browser") rides the 303s until `/callback` captures the code. The browser hits SympAuthy only at
`/authorize`; the pages call the Flow API server-side.

Lifecycle (page URLs must be in startup config, so the server binds first): `forClient(Client)` →
`newFlow().with*Handler(...)` → `container.withFlows(registry)` → `container.start()` → `flow.run()` →
`AuthorizationResult` → `exchange()`. Authorize/token endpoints come from the **discovery document**,
never hardcoded. Register one flow per run and run them in order (sign up, then sign in as that user):
each `run()` starts a fresh browser session.

Rules when extending:

- **`SympauthyContainer.withFlows(registry)`** is the one place the core container depends on the flow
  package (chosen for ergonomics). It contributes **only** the `flows.<id>` definition, via
  `withProperties`. The **client is the caller's** to define (`clients.<id>` with
  `registry.clientId()`, `authorizationFlow` `registry.flowId()`, `allowed-redirect-uris` including
  `registry.redirectUri()`).
- **`FlowApiClient` lives in the `client` package**, not `flow` — it is an HTTP client for a server API,
  like `TokenClient`, and both throw `SympauthyApiException`. It encapsulates the state transport:
  **GET carries the state as `?state=<jwt>`, POST carries it in an `Authorization: State <jwt>` header.**
- **Registry vs. flow split:** the registry owns shared, container-facing state (server/port,
  `clientId`/`flowId`/`scopes`, `attach`/`flowProperties`, dispatch routing); `InteractiveFlow` carries
  only per-run callbacks and delegates `run()` to `registry.run(this)`.
- **Callbacks are segregated single-method interfaces** (`SignInHandler`, `SignUpHandler`,
  `ClaimsHandler`, `ValidationCodeHandler`, `StepListener`, …), registered per flow. A page reached
  without its handler throws; `StepListener` observes *every* page. Do not collapse the split.
- **Prefer `withAuthorizationParam(name, value)` over adding a named setter** for each new spec
  parameter (`claims`, `max_age`, `prompt`, …). It is applied *after* the computed parameters and
  url-encoded. `withNonce`/`withInvitationToken` stay as the ergonomic spelling for theirs.
  `RESERVED_AUTHORIZATION_PARAMS` (`response_type`, `client_id`, `redirect_uri`, `scope`, `state`,
  `code_challenge`, `code_challenge_method`) are **refused** — they are what makes
  `AuthorizationResult.exchange()` work afterwards.
- **PKCE `S256` is always sent**, for public and confidential clients alike. A confidential client's
  secret goes as `client_secret_post` (default) or `client_secret_basic`; the config key is **`secret`**
  and `public` defaults to `false`. `AuthorizationResult.codeVerifier()` exposes the run's verifier, so
  a scenario can issue its own token request (`ExchangeCodeWithOwnTokenRequestIT`) — prefer that over
  opening the reserved `code_challenge` params.
- **Traversed steps accumulate on the flow**: `emit(...)` appends each `FlowStep` before notifying the
  listener, and `stepTypes()` exposes the path — the boilerplate-free way to assert on it.
  `StepListener` stays for reacting to a step as it happens.
- **Coverage:** password happy path, the confirm step, and TOTP MFA (auto-driven via the
  dependency-free `Totp`). `/validate-claims` throws `UnsupportedFlowStepException` — the seam for a
  future email/SMS-validation tier. `flowProperties()` **always** emits `confirm` and the four `mfa-*`
  page URLs (harmless when off), so enabling MFA is just `container.withMfa()`.
- **Link-driven runs for server-initiated flows:** the test calls the entry point itself, then
  `driveFrom(startUrl, successUrl, cancelUrl)` + `drive()` → `FlowResult`/`FlowOutcome`. Such a flow
  resolves its pages from `templates.clients.default.authorization-flow`, so point that at the registry.
- **Flow keys are flat and validated at startup** — `flows.<id>.{type, sign-in, sign-up, collect-claims,
  validate-claims, error}` must all be present, else `/authorize` returns HTTP 500 `config.invalid`
  while the container still boots (the readiness printer only logs the errors).
- **Sending vs. echoing:** the module only *sends* the nonce, extra authorize params and the client
  secret — those are verified Docker-free. Proving the server echoes/accepts them belongs in SympAuthy's
  own integration tests, not here.

## Dependency versioning

Dependencies live in the version catalog `gradle/libs.versions.toml`, referenced via `libs.*`.

- The catalog keeps **two** Testcontainers versions on purpose: `testcontainers-min` (`2.0.0`), which the
  `api` dependency is pinned to, and `testcontainers` (`2.0.5`), which tests build against. Declaring
  `api` against the **lowest** supported release widens compatibility (Gradle resolves to the highest
  requested version). The module is Testcontainers **2.x only**.
- The JSON parser (`minimal-json`) is **bundled and relocated**, not exposed: a dedicated `shade`
  configuration (extended into `compileOnly`/`testImplementation`) plus `com.gradleup.shadow` relocating
  it into `com.sympauthy.testcontainers.internal.json` — where our untouched `JsonCodec` wrapper already
  lives. The published artifact is that shadow jar and the POM is hand-built (`pom.withXml`) to list
  **only** `testcontainers`. Prefer this shade-and-relocate approach for any new runtime dependency.
  Verify with `jar tf` (classes under `internal/json/`) and `generatePomFileForMavenPublication`.
- `nimbus-jose-jwt` is **test-only** (parses id_tokens in the flow ITs) — neither shaded nor published.
- **`JsonCodec` is the only class that touches the parser**, so swapping parsers stays a one-file change.

## Repository workflow

**Merging PRs** — always squash, and leave no branch behind (remote *and* local); afterwards the working
copy sits on `main` at the merged history:

```bash
gh pr merge <number> --squash --delete-branch
git checkout main && git pull
git branch -d <branch>     # safety net; --delete-branch usually removes it already
```

**Cutting a release** — the manually dispatched `Release` workflow (`.github/workflows/release.yml`)
reads the version straight from `gradle.properties`. During a cycle `version` sits at a patch
placeholder (e.g. `0.6.1` after `0.6.0` shipped): that marks `main` as past the last release, it is
**not** the next version. The release version is the caller's choice, so the dance is bump → dispatch →
bump again, both bumps committed **straight to `main`** (no PR):

```bash
sed -i '' 's/^version=.*/version=0.7.0/' gradle.properties
git commit -am "Bump version to 0.7.0" && git push origin main

gh workflow run release.yml --ref main
gh run list --workflow=release.yml --limit 1          # grab the run id
gh run watch <run-id> --exit-status --interval 15

sed -i '' 's/^version=.*/version=0.7.1/' gradle.properties
git commit -am "Bump version to 0.7.1 for next cycle" && git push origin main
```

The workflow refuses `-SNAPSHOT` and any version already in GitHub Packages, runs unit **and**
integration tests, publishes, then tags `v<version>` and creates the release — tag and release only
after a successful publish, so a failed run leaves nothing behind.

- **A published version is final**: the duplicate check blocks a re-release, so confirm the number first.
- **`skip_integration_tests=true` is for one case only** — the SympAuthy image the ITs need isn't
  published yet. Otherwise leave it off; green ITs on the last PR run are the signal the nightly fits.
- **Nothing else carries the version**: the README snippet is a literal `x.x.x` and there is no
  CHANGELOG, so `gradle.properties` is the single edit.
