plugins {
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

gradlePlugin {
    plugins {
        create("hytest") {
            id = "gg.ginco.hytest"
            implementationClass = "gg.ginco.hytest.gradle.HytestGradlePlugin"
            displayName = "Hytest"
            description = "Testing framework for Hytale plugins"
            tags.set(listOf("hytale", "testing"))
        }
    }
}
