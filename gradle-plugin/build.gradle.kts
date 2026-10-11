plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.3.21"
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

// `java-gradle-plugin` wires `pluginUnderTestMetadata` into the test runtime
// classpath, so TestKit's withPluginClasspath() applies the plugin straight
// from this checkout — no publishing needed.
val junitVersion = property("junitPlatformVersion")

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
    // Injected (not duplicated) so the convention sync-check below always
    // compares against the actual release version of this build.
    systemProperty("hytest.projectVersion", project.version.toString())
}
