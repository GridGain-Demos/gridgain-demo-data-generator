// OCI image build for the GG9 data generator.
//
// This module is image-only: it does not produce a publishable Maven artifact.
// Its job is to bundle `data-generator-gg9` along with the GG9 runtime into a
// container image via Jib. The sibling `data-generator-gg9` JAR declares
// `ignite-client` as an `api` dependency so it transits onto this image's runtime
// classpath without restating it here.

plugins {
    java
    id("com.google.cloud.tools.jib") version "3.4.4"
}

description = "OCI image build for the GG9 data generator"

dependencies {
    implementation(project(":data-generator-gg9"))
}

// Default registry: the public GridGain-Demos GHCR org. End users pull from here;
// they never push. Override `imageRegistry` for local Jib builds (e.g.
// `jibBuildTar` for air-gapped distribution) or for forks publishing to a
// private registry.
val DEFAULT_REGISTRY = "ghcr.io/gridgain-demos"
val SOURCE_REPO_URL  = "https://github.com/GridGain-Demos/gridgain-demo-data-generator"

val imageRegistry = ((findProperty("imageRegistry") as String?)?.trimEnd('/'))
    ?.takeIf { it.isNotBlank() }
    ?: DEFAULT_REGISTRY
val imageName    = "gridgain-data-generator-gg9"
val imageTag     = ((findProperty("imageTag") as String?)?.takeIf { it.isNotBlank() })
    ?: project.version.toString()
val targetImage  = "$imageRegistry/$imageName:$imageTag"

jib {
    from {
        image = "eclipse-temurin:17-jre"
    }
    to {
        image = targetImage
        tags  = setOf(imageTag)
        // Auth: reads gridgainGhcrUsername / gridgainGhcrPassword from
        // ~/.gradle/gradle.properties (or -P flags), or falls through to Jib's
        // standard credential helpers (~/.docker/config.json, gcloud, etc.)
        // when those properties aren't set.
        val ghcrUser = (findProperty("gridgainGhcrUsername") as String?)?.takeIf { it.isNotBlank() }
        val ghcrPass = (findProperty("gridgainGhcrPassword") as String?)?.takeIf { it.isNotBlank() }
        if (ghcrUser != null && ghcrPass != null) {
            auth {
                username = ghcrUser
                password = ghcrPass
            }
        }
    }
    container {
        mainClass = "com.gridgain.demo.datagen.cli.Gg9Main"
        labels.set(mapOf(
            "org.opencontainers.image.title"       to "GridGain Demo Data Generator (GG9)",
            "org.opencontainers.image.version"     to imageTag,
            "org.opencontainers.image.source"      to SOURCE_REPO_URL,
            "org.opencontainers.image.description" to "Data-generator runtime targeting a GridGain 9 cluster. Run inside Kubernetes as a Job or Pod via the demo plugin's data_generator section.",
            "org.gridgain.demo.major-version"      to "9",
        ))
    }
}

// Image-build modules don't publish to Maven. The root `subprojects { ... }`
// block applies `maven-publish` to every subproject and creates a default
// publication; rather than restructure that, we simply disable the publish
// tasks here so they're no-ops for this module.
tasks.withType<PublishToMavenLocal>().configureEach { enabled = false }
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
