# testcontainers-sympauthy
Testcontainers module for SympAuthy, an OAuth 2.0 / OpenID Connect authorization server

`testcontainers-sympauthy` runs a real SympAuthy server inside a Docker container so it can be driven
from JVM unit and integration tests — pointing the system under test at a genuine OAuth 2.1 / OpenID
Connect provider instead of a mock. It also ships a browser-free driver for SympAuthy's interactive
login flow (sign-up, sign-in, claims, confirm, TOTP MFA) to obtain real authorization codes and
tokens end-to-end. It is a test-only dependency; the only dependency you inherit is Testcontainers
itself.

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
