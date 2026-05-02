plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.gridgain.demo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // DataFaker is wired now so later plans don't need to revisit build config.
    implementation("net.datafaker:datafaker:2.5.4")

    // Jackson stack for YAML parsing — mirrors the plugin's choice.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")

    // JSONSchema validation — mirrors the plugin's choice.
    implementation("com.networknt:json-schema-validator:1.5.9")

    // SLF4J API for logging. No binding in main; tests use slf4j-simple.
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

// Project-wide rule: SnakeYAML forced to 1.33 to prevent Android variant conflicts.
configurations.all {
    resolutionStrategy {
        force("org.yaml:snakeyaml:1.33")
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
