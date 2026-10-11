package gg.ginco.hytest.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end check of the #2 fix in the same shape consumers hit it: the
 * plugin applied from a `plugins { }` block, so apply() runs before the
 * build-script body. A `hytest { version = … }` assignment made *after* the
 * plugin is applied must drive the declared runtime/junit5 dependencies, not
 * just the merged jar's manifest.
 */
class HytestGradlePluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `build-script version assignment drives dependency declarations`() {
        writeBuildScript(hytestBlock = """hytest { version = "9.9.9" }""")

        val output = runNotationsTask()

        // Pre-fix, apply()-time capture baked the convention ("0.1.0") into
        // these strings and the assignment was ignored.
        assertDependencyPresent(output, "hytestRuntimePlugin", "gg.ginco.hytest:runtime:9.9.9")
        assertDependencyPresent(output, "hytaleTestImplementation", "gg.ginco.hytest:runtime:9.9.9")
        assertDependencyPresent(output, "hytaleTestRuntimeOnly", "gg.ginco.hytest:junit5:9.9.9")
    }

    @Test
    fun `without an explicit version the convention drives dependency declarations`() {
        writeBuildScript(hytestBlock = "")

        val output = runNotationsTask()
        val projectVersion = System.getProperty("hytest.projectVersion")

        assertDependencyPresent(output, "hytestRuntimePlugin", "gg.ginco.hytest:runtime:$projectVersion")
        assertDependencyPresent(output, "hytaleTestImplementation", "gg.ginco.hytest:runtime:$projectVersion")
        assertDependencyPresent(output, "hytaleTestRuntimeOnly", "gg.ginco.hytest:junit5:$projectVersion")
    }

    /**
     * Prints the declared (unresolved) dependencies of the configurations the
     * plugin populates. Declarations only — no repositories needed, so the
     * runner stays offline and fast.
     */
    private fun runNotationsTask(): String {
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("hytestDependencyNotations")
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":hytestDependencyNotations")?.outcome)
        return result.output
    }

    private fun assertDependencyPresent(output: String, configuration: String, gav: String) {
        assertTrue(
            output.lineSequence().any { it.startsWith("DEP $configuration ") && it.endsWith(gav) },
            "expected $configuration to declare $gav; output was:\n$output")
    }

    private fun writeBuildScript(hytestBlock: String) {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("gg.ginco.hytest") }

            $hytestBlock

            tasks.register("hytestDependencyNotations") {
                doLast {
                    listOf("hytestRuntimePlugin",
                           "hytaleTestImplementation",
                           "hytaleTestRuntimeOnly").forEach { name ->
                        configurations.getByName(name).dependencies.forEach { dep ->
                            println("DEP ${'$'}{name} ${'$'}{dep.group}:${'$'}{dep.name}:${'$'}{dep.version}")
                        }
                    }
                }
            }
            """.trimIndent()
        )
    }
}
