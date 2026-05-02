plugins {
    kotlin("jvm") version "2.2.20"
}

group = "com.gridgain.demo.converter"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("net.datafaker:datafaker:2.5.4")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}