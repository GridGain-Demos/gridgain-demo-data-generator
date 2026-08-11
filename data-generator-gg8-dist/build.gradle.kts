// Installable distribution for the GG8 data generator.
//
// This module is distribution-only: like its `-image` sibling it holds no source. Its job is to bundle
// `data-generator-gg8` and the GG8 runtime into a self-contained archive that can be unpacked onto a
// machine and run under systemd, which is how the demo toolkit's `hosts` platform installs everything
// else it deploys (the JDK, Prometheus, Grafana, the OpenTelemetry Collector).
//
// Why an archive and not the container image: the `hosts` platform is deliberately systemd + archives.
// Reusing the image would make a container runtime a prerequisite of every machine, which is the thing
// that platform exists to avoid.
//
// Two dist modules rather than one, mirroring the `-image` split: the GG8 and GG9 thin clients pull
// incompatible Ignite runtimes, and one combined archive would put both on the same classpath.

plugins {
    application
}

description = "Installable tar.gz distribution for the GG8 data generator"

dependencies {
    implementation(project(":data-generator-gg8"))
    // SLF4J binding, for the same reason the image declares it: without a binding slf4j-api falls back
    // to its NOP provider, prints one diagnostic line, and silently drops every LOG.info(...) the
    // generator makes. slf4j-simple writes to stderr at INFO, which is what systemd's
    // StandardOutput=append: captures into the run log.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("com.gridgain.demo.datagen.cli.Gg8Main")

    // Fixes the launcher name and the archive's root directory to `data-generator-gg8`, rather than
    // letting them default to the module name. The toolkit's `install-archive.sh` is told the root entry
    // to strip, so a stable, predictable name is part of the contract with it.
    applicationName = "data-generator-gg8"

    // GG8 only. The Ignite 2.x thin client reflects on java.nio internals, which JDK 17 denies without
    // these — the same two flags data-generator-gg8's test task and the jib image entrypoint both carry.
    // Omitting them here would produce an archive that installs cleanly and then fails at first
    // connection with an InaccessibleObjectException far from its cause.
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

// Gradle's distTar emits an uncompressed .tar, which the toolkit does not accept: ArchiveFormat is
// tar.gz / tar.xz / zip. Compressing here rather than asking the consumer to handle a fourth format.
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
