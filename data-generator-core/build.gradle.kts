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

    // Fabric8 K8s client — used by the distributed-mode Coordinator for lease-based leader
    // election and watching worker status ConfigMaps. Implementation (not api) so consumers
    // don't transitively inherit it; single-pod scenarios never touch this dep.
    implementation(platform("io.fabric8:kubernetes-client-bom:6.13.4"))
    implementation("io.fabric8:kubernetes-client")

    // Kafka producer for the optional live-metrics export (KafkaMetricsSink). Implementation
    // (not api) so consumers don't inherit it; only active when ops.yaml declares a metrics block.
    implementation("org.apache.kafka:kafka-clients:3.8.0")

    // HdrHistogram for the whole-run latency histogram published on the metrics topic. `api` (not
    // implementation) because the encoded histogram is part of the wire contract: a consumer that
    // decodes a snapshot needs the same library, and the UI does exactly that.
    api("org.hdrhistogram:HdrHistogram:2.2.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("io.fabric8:kubernetes-server-mock")
    // Fabric8's bundled mockwebserver is 3.12.12 but the rest of the classpath
    // resolves OkHttp to 4.12.0 (via the OTel exporter). Pin mockwebserver to
    // the matching 4.12.0 so KubernetesMockServerExtension can initialise.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
