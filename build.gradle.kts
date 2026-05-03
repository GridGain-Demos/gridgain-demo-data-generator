plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.gridgain.demo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        name = "GridGain External Repository"
        url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
    }
}

dependencies {
    implementation("net.datafaker:datafaker:2.5.4")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    implementation("com.networknt:json-schema-validator:1.5.9")

    implementation("org.slf4j:slf4j-api:2.0.13")

    // Plan 6 — GG8 KV target
    implementation("com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT")
    implementation("org.gridgain:ignite-core:8.9.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

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
