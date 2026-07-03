plugins {
    `java-library`
    kotlin("jvm") version "2.3.21"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:${property("hytaleServerVersion")}")
}

tasks.jar {
    // Bundle Kotlin stdlib — the server does not provide it.
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
