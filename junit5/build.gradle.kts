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
    api("org.junit.platform:junit-platform-engine:${property("junitPlatformVersion")}")
    // Ship a matched launcher with the engine: consumers adding
    // gg.ginco.hytest:junit5 now resolve its launcher transitively — the
    // gradle plugin's pinned default only keys older junit5 artifacts.
    api("org.junit.platform:junit-platform-launcher:${property("junitPlatformVersion")}")
    implementation("org.ow2.asm:asm:${property("asmVersion")}")
}
