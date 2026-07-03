plugins {
    `java-library`
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
