import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // Applied at the root with `apply false` so the publish plugin and the
    // Kotlin plugin share a classloader (vanniktech reflects into KGP).
    kotlin("jvm") version "2.3.21" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "gg.ginco.hytest"
    version = "0.1.1"
}

subprojects {
    repositories {
        maven("https://maven.hytale.com/release")
        maven("https://maven.hytale.com/pre-release")
        mavenCentral()
    }

    // Every Gradle module here is published (runtime, junit5, gradle-plugin);
    // the non-Gradle dirs (Server/, Common/, run/, example/) are not modules.
    apply(plugin = "com.vanniktech.maven.publish")

    // The HytestGradlePlugin adds these exact coordinates to consumer
    // projects ("gg.ginco.hytest:runtime" / ":junit5"), so the published
    // group/artifacts must stay in lockstep with it.
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // 0.37: Central Portal is the default target; automaticRelease
        // drops the staging repo straight into Central after validation.
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()

        pom {
            name = "Hytest ${project.name}"
            description = when (project.name) {
                "runtime" -> "Server-side runtime for Hytest: simulated players, test world factory, in-server test runner"
                "junit5" -> "JUnit Platform TestEngine that boots a real Hytale server for each test run"
                else -> "Gradle plugin that wires the hytaleTest source set, server provisioning and test execution"
            }
            url = "https://github.com/ginco-org/hytest"
            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "ginco"
                    name = "GINCo"
                    url = "https://ginco.gg"
                }
            }
            scm {
                connection = "scm:git:git://github.com/ginco-org/hytest.git"
                developerConnection = "scm:git:ssh://github.com/ginco-org/hytest.git"
                url = "https://github.com/ginco-org/hytest"
            }
        }
    }
}
