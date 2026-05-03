import java.util.concurrent.TimeUnit

// Root project for gridgain-demo-data-generator.
//
// No source lives at the root; only subprojects.
// Subproject-specific dependencies and JVM args are declared in each subproject's
// own build.gradle.kts (filled in by Plan 7.5 Tasks 2, 4, and 5).

plugins {
    kotlin("jvm") version "2.2.20" apply false
    `maven-publish`
}

allprojects {
    group = "com.gridgain.demo"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        mavenLocal()
        maven {
            name = "GridGain External Repository"
            url = uri("https://maven.gridgain.com/nexus/content/repositories/external")
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(17)
    }

    // SnakeYAML is forced to 1.33 across all configurations to prevent
    // Android variant conflicts. SNAPSHOT caching is disabled intentionally.
    configurations.all {
        resolutionStrategy {
            force("org.yaml:snakeyaml:1.33")
            cacheChangingModulesFor(0, TimeUnit.SECONDS)
            cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = "gridgain-${project.name}"
            }
        }
        repositories {
            maven {
                name = "GridGainNexus"
                if (!project.version.toString().endsWith("-SNAPSHOT")) {
                    throw GradleException(
                        "Publishing to GridGain Nexus currently supports SNAPSHOT versions only. " +
                            "Current version is '${project.version}'. Either set a -SNAPSHOT version, or " +
                            "request a release repository from IT and update build.gradle.kts to target it."
                    )
                }
                url = uri("https://nexus.gridgain.com/repository/public-snapshots/")
                credentials {
                    username = project.findProperty("gridgainNexusUsername") as String? ?: ""
                    password = project.findProperty("gridgainNexusPassword") as String? ?: ""
                }
            }
        }
    }
}
