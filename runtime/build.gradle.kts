plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:${property("hytaleServerVersion")}")
}
