// data-generator-gg8 — GG8-flavored runtime. ignite-core 8.9.18 + gg8-client-finder 0.5.0-SNAPSHOT. No GG9 deps.
plugins {
    `java-library`
}

dependencies {
    api(project(":data-generator-core"))
    // 0.7.0-SNAPSHOT, not 0.5.0: the plugin writes client-endpoints.yaml at schema_version 2, and
    // ClientEndpointsLoader hard-throws SchemaVersionMismatchException on anything else. Every finder
    // before 0.7.0 expects version 1, so a generator built against one refuses the file the plugin
    // produces — on Kubernetes as well as on hosts. 0.7.0 is also the first version with the
    // deployment_kind discriminator, without which a host cluster's entry cannot be read at all.
    // The public API is unchanged across the bump (DemoAddressFinder(String) + getAddresses()).
    api("com.gridgain.demo:gg8-client-finder:0.7.0-SNAPSHOT")
    api("org.gridgain:ignite-core:8.9.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // GG8 thin client (Apache Ignite 2.x) reflects on java.nio internals; JDK 17 needs --add-opens. (Plan 6.)
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
