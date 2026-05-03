// data-generator-core — GG-agnostic data generator runtime. No ignite-* deps.
plugins {
    `java-library`
}

dependencies {
    api("net.datafaker:datafaker:2.5.4")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    api("com.networknt:json-schema-validator:1.5.9")
    api("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
}
