plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
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

    // Plan 7 — GG9 KV target
    // gg9-client-finder declares ignite-client as compileOnly; we mirror that here so
    // GG8's ignite-core (8.9.18) and GG9's ignite-client (9.1.3) never end up on the
    // SAME runtime classpath (their org.apache.ignite.* packages overlap incompatibly).
    // The CLI / plugin chooses which client jar to add at scenario-run time based on
    // the resolved target kind. See Plan 7 Option E and follow-up F8.
    implementation("com.gridgain.demo:gg9-client-finder:0.0.5-SNAPSHOT")
    compileOnly("org.gridgain:ignite-client:9.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))

    // Plan 7 — env-gated GG9 integration tests need the GG9 client at test runtime.
    testImplementation("org.gridgain:ignite-client:9.1.3")
}

configurations.all {
    resolutionStrategy {
        force("org.yaml:snakeyaml:1.33")
    }
}

tasks.test {
    useJUnitPlatform()
    // GG8 (Apache Ignite 2.x) thin client uses reflection on java.nio internals;
    // JDK 17's strong encapsulation blocks this without the --add-opens flag.
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
