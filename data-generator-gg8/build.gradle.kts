// data-generator-gg8 — GG8-flavored runtime. ignite-core 8.9.18 + gg8-client-finder 0.0.5-SNAPSHOT. No GG9 deps.
plugins {
    `java-library`
}

dependencies {
    api(project(":data-generator-core"))
    api("com.gridgain.demo:gg8-client-finder:0.0.5-SNAPSHOT")
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
