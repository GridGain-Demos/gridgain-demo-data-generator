// Installable distribution for the GG9 data generator.
//
// Distribution-only, holding no source — see data-generator-gg8-dist/build.gradle.kts for why the
// `hosts` platform wants an archive rather than the container image, and why the GG8 and GG9
// distributions are separate modules.

plugins {
    application
}

description = "Installable tar.gz distribution for the GG9 data generator"

dependencies {
    implementation(project(":data-generator-gg9"))
    // See the note in data-generator-gg8-dist: without a binding, every log line is silently dropped.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("com.gridgain.demo.datagen.cli.Gg9Main")
    applicationName = "data-generator-gg9"

    // No --add-opens here, unlike GG8. The GG9 thin client does not reflect on java.nio internals, which
    // is the same reason data-generator-gg9's test task configures no jvmArgs. Copying the GG8 flags
    // across would grant permissions nothing asks for.
}

// Gradle's distTar emits an uncompressed .tar; the toolkit accepts tar.gz / tar.xz / zip only.
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
