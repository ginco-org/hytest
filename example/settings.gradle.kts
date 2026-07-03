pluginManagement {
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    includeBuild("..")
    repositories {
        maven("https://maven.hytale.com/release")
        maven("https://maven.hytale.com/pre-release")
        mavenCentral()
    }
}

rootProject.name = "hytest-example"
