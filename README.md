# testcontainers-sympauthy

Testcontainers module for [SympAuthy](https://sympauthy.github.io/), an OAuth 2.1 / OpenID Connect
authorization server.

It runs a real SympAuthy server in a Docker container so JVM tests can point the system under test at
a genuine OIDC provider instead of a mock. It also ships a browser-free driver for SympAuthy's
interactive login flow (sign-up, sign-in, claims, confirm, TOTP MFA) to obtain real authorization
codes and tokens end to end. Test-only, and the only dependency you inherit is Testcontainers itself
— the JSON parser it uses internally is shaded.

```java
try (SympauthyContainer sympauthy = new SympauthyContainer()) {
    sympauthy.start();

    String issuer    = sympauthy.getIssuerUrl();               // http://localhost:<port>
    String discovery = sympauthy.getOpenIdConfigurationUrl();  // .../.well-known/openid-configuration
    // point the system under test at the issuer / discovery document
}
```

## Installation

The library is published to **GitHub Packages**. Even though the package is public, GitHub's Maven
registry **requires authentication for every read**, so you need a
[personal access token](https://github.com/settings/tokens) with the `read:packages` scope (classic
token) — supply it as the password:

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

In GitHub Actions no personal token is needed — grant the job `packages: read` and pass the automatic
`GITHUB_TOKEN` through as `GITHUB_TOKEN`/`GITHUB_ACTOR`, which the fallback above picks up. **Maven**
setup (`settings.xml` server + `pom.xml` repository) and the full CI snippet are in
[Getting started](https://sympauthy.github.io/testcontainers/getting_started).

## Documentation

📖 **Full documentation:** https://sympauthy.github.io/testcontainers/

- [Getting started](https://sympauthy.github.io/testcontainers/getting_started) — install and start your first container
- [Configuration](https://sympauthy.github.io/testcontainers/configuration) — property overrides, environments, config files, datasource
- [Interactive Flow](https://sympauthy.github.io/testcontainers/interactive_flow) — drive the login flow to a code and tokens without a browser
- [Clients](https://sympauthy.github.io/testcontainers/clients) — public and confidential clients
- [Multi-factor Authentication](https://sympauthy.github.io/testcontainers/mfa) — TOTP MFA
- [Invitation](https://sympauthy.github.io/testcontainers/invitation) — bootstrap invitation tokens
- [Admin API](https://sympauthy.github.io/testcontainers/admin) — call the Admin API from tests
- [Server-initiated Flows](https://sympauthy.github.io/testcontainers/server_initiated_flows) — client- or admin-initiated confirm/MFA flows
