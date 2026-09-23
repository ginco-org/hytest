package gg.ginco.hytest.gradle

import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
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

    init {
        version.convention("0.1.0")
        serverDir.convention(project.layout.projectDirectory.dir("run"))
        timeoutSeconds.convention(300L)
        // Server boot is not instant (full JVM + assets), so the first-frame
        // grace needs headroom beyond a healthy boot; it only guards the
        // "jar rejected, nothing will ever arrive" case, never mid-run stalls.
        firstFrameGraceSeconds.convention(90L)
        showServerLogs.convention(false)
        jvmArgs.convention(listOf("-Xmx4G", "-Xms1G"))
    }
}
