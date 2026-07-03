pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.hytale.com/release")
        maven("https://maven.hytale.com/pre-release")
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hytest"

include(":runtime")
include(":junit5")
include(":gradle-plugin")
