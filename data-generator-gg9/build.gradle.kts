// data-generator-gg9 — GG9-flavored runtime. ignite-client 9.1.3 + gg9-client-finder. No GG8 deps.
// The split (Plan 7.5) is what eliminates the FQN collision that drove Plan 7's reflection workaround.
plugins {
    `java-library`
}

dependencies {
    api(project(":data-generator-core"))
    api("com.gridgain.demo:gg9-client-finder:0.5.0-SNAPSHOT")
    api("org.gridgain:ignite-client:9.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}

// GG9 thin client does not need the java.nio --add-opens that GG8 needs.
tasks.test { useJUnitPlatform() }
