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
    implementation("org.ow2.asm:asm:${property("asmVersion")}")
}
