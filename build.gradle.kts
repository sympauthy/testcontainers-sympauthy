plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

val testcontainersVersion = "2.0.5"
val junitVersion = "5.11.4"

dependencies {
    // Exposed to consumers: they write tests against the Testcontainers API.
    api("org.testcontainers:testcontainers:$testcontainersVersion")

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
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
