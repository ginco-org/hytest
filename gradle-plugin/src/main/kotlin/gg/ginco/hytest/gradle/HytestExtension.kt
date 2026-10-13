package gg.ginco.hytest.gradle

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import java.nio.file.Path
import org.gradle.api.provider.Property

abstract class HytestExtension @Inject constructor(project: Project) {

    abstract val version: Property<String>

    abstract val serverDir: DirectoryProperty

    abstract val serverJar: Property<String>

    abstract val assets: Property<String>

    abstract val javaExecutable: Property<String>

    abstract val jvmArgs: ListProperty<String>

    abstract val timeoutSeconds: Property<Long>

    /** How long to wait for the test plugin's first output before failing the run. */
    abstract val firstFrameGraceSeconds: Property<Long>

    abstract val showServerLogs: Property<Boolean>

    /** Extra plugin jars deployed into the test server's mods/ directory
     * next to the test plugin itself — e.g. shared-runtime carrier plugins
     * the tested plugin declares as a hard dependency
     * (gg.ginco:kotlin-for-hytale). Deployed before boot; tests crash loudly
     * if the tested plugin's dependency is missing, so no silent retry. */
    abstract val extraMods: ListProperty<Path>

    init {
        // Keep in lockstep with the release version set in the root build —
        // HytestGradlePluginTest.versionConventionMatchesProjectVersion fails
        // the build when they drift. This default serves consumers that don't
        // set `hytest { version }` at all (the common hygradle case).
        version.convention("0.2.0")
        serverDir.convention(project.layout.projectDirectory.dir("run"))
        timeoutSeconds.convention(300L)
        // Server boot is not instant (full JVM + assets), so the first-frame
        // grace needs headroom beyond a healthy boot; it only guards the
        // "jar rejected, nothing will ever arrive" case, never mid-run stalls.
        firstFrameGraceSeconds.convention(90L)
        showServerLogs.convention(false)
        extraMods.convention(emptyList())
        jvmArgs.convention(listOf("-Xmx4G", "-Xms1G"))
    }
}
