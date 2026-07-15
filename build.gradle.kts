plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

// The Testcontainers version we build and test against.
val testcontainersVersion = "2.0.5"
val junitVersion = "5.11.4"

dependencies {
    // The minimum Testcontainers version exposed to consumers. Declaring the `api` dependency
    // against the lowest supported release (rather than the one we test with) widens
    // compatibility: Gradle resolves to the highest requested version, so consumers already on
    // 2.0.0+ can use this library without being forced up to a newer Testcontainers.
    api("org.testcontainers:testcontainers:2.0.0")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

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

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Container-starting tests (require Docker). Run with: ./gradlew integrationTest
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that start real containers (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter("test")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
