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

    api(platform("io.opentelemetry:opentelemetry-bom:1.42.0"))
    api("io.opentelemetry:opentelemetry-api")
    api("io.opentelemetry:opentelemetry-sdk")
    api("io.opentelemetry:opentelemetry-sdk-metrics")
    api("io.opentelemetry:opentelemetry-sdk-logs")
    api("io.opentelemetry:opentelemetry-exporter-otlp")
    // Prometheus exporter is incubator-coordinated until BOM 1.45; explicit version
    // matches the BOM-side incubator track. Re-pin when the BOM stabilises it.
    api("io.opentelemetry:opentelemetry-exporter-prometheus:1.42.0-alpha")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}
