plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    // The minimum Testcontainers version exposed to consumers. The `libs.testcontainers` alias is
    // pinned to the lowest supported release (rather than the one we test with) to widen
    // compatibility: Gradle resolves to the highest requested version, so consumers already on
    // 2.0.0+ can use this library without being forced up to a newer Testcontainers.
    api(libs.testcontainers)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// Container-starting integration tests live in their own source set (src/integrationTest/java)
// so they compile and run independently of the fast, Docker-free unit tests in src/test/java.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

// Reuse the unit tests' declared dependencies (JUnit, Testcontainers, SLF4J) for the
// integration tests rather than redeclaring them.
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
