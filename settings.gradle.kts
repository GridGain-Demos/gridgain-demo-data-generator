pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "GridGain External Repository"
            url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "gridgain-demo-data-generator"

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":data-generator-core",
    ":data-generator-gg8",
    ":data-generator-gg9",
)
