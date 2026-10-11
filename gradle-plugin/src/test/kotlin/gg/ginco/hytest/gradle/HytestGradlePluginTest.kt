package gg.ginco.hytest.gradle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression guards for #2: the plugin 0.1.1 release still resolved the
 * runtime/junit5 artifacts from a hardcoded "0.1.0" convention captured at
 * apply() time, so consumers pinning 0.1.1 silently booted the 0.1.0 runtime.
 */
class HytestGradlePluginTest {

    /**
     * The `hytest { version }` convention must stay in lockstep with the
     * release version — it is the version Maven-Central consumers resolve
     * when they don't set the property. Bump it in HytestExtension.kt with
     * every release; this check fails `./gradlew build` otherwise.
     */
    @Test
    fun versionConventionMatchesProjectVersion() {
        val project: Project = ProjectBuilder.builder().build()
        project.pluginManager.apply(HytestGradlePlugin::class.java)

        val ext = project.extensions.getByType(HytestExtension::class.java)
        assertEquals(
            System.getProperty("hytest.projectVersion"),
            ext.version.orNull,
            "hytest { version } convention drifted from the release version — " +
                "update version.convention(...) in HytestExtension.kt")
    }
}
