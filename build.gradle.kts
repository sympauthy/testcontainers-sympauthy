import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

// Dependencies bundled into our jar with their packages relocated, so they stay invisible to
// consumers: no additions to a consumer's classpath, no possible version clash. Keeping them in this
// dedicated configuration (rather than `api`/`implementation`) is what keeps them out of the
// published POM. `compileOnly` and `testImplementation` extend it so the main sources and the tests
// still compile and run against the original (un-relocated) classes; only the published `shadowJar`
// relocates them.
val shade: Configuration by configurations.creating
configurations.named("compileOnly") { extendsFrom(shade) }
configurations.named("testImplementation") { extendsFrom(shade) }

// The Testcontainers version consumers inherit (the `api` floor, testcontainers-min). Read from the
// catalog here so it can be reused in the hand-built publication POM below.
val testcontainersVersion =
    versionCatalogs.named("libs").findVersion("testcontainers-min").get().requiredVersion

dependencies {
    // The minimum Testcontainers version exposed to consumers. The `libs.testcontainers` alias is
    // pinned to the lowest supported release (rather than the one we test with) to widen
    // compatibility: Gradle resolves to the highest requested version, so consumers already on
    // 2.0.0+ can use this library without being forced up to a newer Testcontainers.
    api(libs.testcontainers)

    // Tiny JSON parser used only by the flow client. Shaded + relocated into
    // com.sympauthy.testcontainers.internal.json by the shadowJar task below, so it never reaches a
    // consumer's classpath or the published POM.
    shade(libs.minimal.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.junit.jupiter)
    // Used by the integration test (which inherits testImplementation) to parse/validate id_tokens.
    testImplementation(libs.nimbus.jose.jwt)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// Container-starting integration tests live in their own source set (src/integrationTest/java)
// so they compile and run independently of the fast, Docker-free unit tests in src/test/java.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

// Reuse the unit tests' declared dependencies (JUnit, Testcontainers, SLF4J, and — transitively —
// the shaded JSON parser) for the integration tests rather than redeclaring them.
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

java {
    withSourcesJar()
    withJavadocJar()
}

// Emit Java 17-compatible bytecode regardless of the JDK running the build, matching the
// module's minimum supported runtime (see README "Requirements"). Using --release also
// prevents accidentally compiling against APIs newer than Java 17.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// The published artifact is the shadow jar: our classes plus the relocated JSON parser. Bundle ONLY
// the `shade` configuration (not the whole runtime classpath) so Testcontainers stays a normal `api`
// dependency instead of being copied in.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    configurations = listOf(shade)
    relocate("com.eclipsesource.json", "com.sympauthy.testcontainers.internal.json")
}

// Move the thin jar aside so it doesn't collide with the shadow jar (which takes the empty
// classifier and becomes the primary artifact).
tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Container-starting tests (require Docker). Run with: ./gradlew integrationTest
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that start real containers (requires Docker)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter("test")
}

// We publish a hand-built POM for the shaded jar; a Gradle Module Metadata (.module) file describing
// variants would contradict it, so disable it.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // The relocated fat jar, plus sources/javadoc (which carry only our own sources).
            artifact(tasks.named("shadowJar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            // The shaded JSON parser is deliberately absent from the POM: Testcontainers is the only
            // dependency a consumer inherits.
            pom {
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    dependencies.appendNode("dependency").apply {
                        appendNode("groupId", "org.testcontainers")
                        appendNode("artifactId", "testcontainers")
                        appendNode("version", testcontainersVersion)
                        appendNode("scope", "compile")
                    }
                }
            }
        }
    }

    repositories {
        // Publish target for the release workflow. Credentials come from the standard GitHub Actions
        // env vars, so local builds simply can't publish (which is correct) while the workflow can.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/sympauthy/testcontainers-sympauthy")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
