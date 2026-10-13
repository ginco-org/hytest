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
    // Bytecode pre-filter for ClasspathTestScanner — parse annotations without
    // class loading (the plain-reflect pass otherwise resolved every signature
    // in the entire server jar).
    implementation("org.ow2.asm:asm:${property("asmVersion")}")
}

tasks.jar {
    // Bundle Kotlin stdlib — the server does not provide it.
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
