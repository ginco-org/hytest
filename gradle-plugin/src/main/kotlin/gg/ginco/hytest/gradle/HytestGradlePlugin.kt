package gg.ginco.hytest.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import java.io.File

class HytestGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply(JavaPlugin::class.java)

        val ext = project.extensions.create("hytest", HytestExtension::class.java, project)
        applyHygradleDefaults(project, ext)

        val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
        val sourceSets = javaExt.sourceSets
        val main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val hytaleTest = sourceSets.create(SOURCE_SET)

        wireSourceSetToMain(project, hytaleTest, main)
        wireServerApi(project, hytaleTest)

        val hytestVersion = ext.version.get()
        val runtimePlugin = project.configurations.create("hytestRuntimePlugin") { config ->
            config.setCanBeConsumed(false)
            config.setCanBeResolved(true)
            config.description = "hytest-runtime jar merged into the deployed plugin"
        }

        val junitPlatform = resolveJunitPlatformVersion(project)
        project.dependencies.add(runtimePlugin.name, "gg.ginco.hytest:runtime:$hytestVersion")
        project.dependencies.add(hytaleTest.implementationConfigurationName,
            "gg.ginco.hytest:runtime:$hytestVersion")
        project.dependencies.add(hytaleTest.runtimeOnlyConfigurationName,
            "gg.ginco.hytest:junit5:$hytestVersion")
        project.dependencies.add(hytaleTest.runtimeOnlyConfigurationName,
            "org.junit.platform:junit-platform-launcher:$junitPlatform")

        val mergedJar = registerMergedJarTask(project, runtimePlugin, hytaleTest, main)
        registerTestTask(project, ext, sourceSets, mergedJar)
    }

    private fun wireSourceSetToMain(project: Project, hytaleTest: SourceSet, main: SourceSet) {
        project.configurations.getByName(hytaleTest.implementationConfigurationName)
            .extendsFrom(project.configurations.getByName("implementation"))
        project.configurations.getByName(hytaleTest.compileOnlyConfigurationName)
            .extendsFrom(project.configurations.getByName("compileOnly"))
        project.configurations.getByName(hytaleTest.runtimeOnlyConfigurationName)
            .extendsFrom(project.configurations.getByName("runtimeOnly"))
        project.dependencies.add(hytaleTest.implementationConfigurationName, main.output)
    }

    private fun wireServerApi(project: Project, hytaleTest: SourceSet) {
        val hytaleApi = project.configurations.findByName("hytaleApi") ?: return
        project.configurations.getByName(hytaleTest.compileOnlyConfigurationName).extendsFrom(hytaleApi)
    }

    private fun registerMergedJarTask(
        project: Project, runtimePlugin: Configuration, hytaleTest: SourceSet, main: SourceSet
    ): TaskProvider<Jar> =
        project.tasks.register("createHytaleTestPlugin", Jar::class.java) { jar ->
            jar.description = "Builds the merged Hytest plugin jar (runtime + hytaleTest classes) for server deployment."
            jar.archiveBaseName.set("Hytest")
            jar.archiveClassifier.set("")
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            jar.from(project.provider {
                runtimePlugin.files.filter { it.name.endsWith(".jar") }.map { project.zipTree(it) } as Iterable<*>
            })
            jar.from(hytaleTest.output)
            jar.from(main.output.classesDirs)
        }

    private fun registerTestTask(
        project: Project, ext: HytestExtension, sourceSets: SourceSetContainer, mergedJar: TaskProvider<Jar>
    ) {
        val hytaleTest = sourceSets.getByName(SOURCE_SET)
        project.tasks.register("hytaleTest", Test::class.java) { test ->
            test.group = "verification"
            test.description = "Runs @HytaleTest methods inside a Hytale server."
            test.testClassesDirs = hytaleTest.output.classesDirs
            test.classpath = hytaleTest.runtimeClasspath
            test.useJUnitPlatform()
            test.dependsOn(mergedJar)

            test.systemProperty("hytest.serverDir",
                project.layout.buildDirectory.dir("hytale-server").get().asFile.absolutePath)
            test.systemProperty("hytest.jvmArgs", ext.jvmArgs.get().joinToString(" "))
            test.systemProperty("hytest.timeoutSeconds", ext.timeoutSeconds.get().toString())
            test.systemProperty("hytest.showServerLogs", ext.showServerLogs.get().toString())
            if (ext.javaExecutable.isPresent) {
                test.systemProperty("hytest.java", ext.javaExecutable.get())
            }

            val serverJar = resolveServerJar(project, ext)
            val assets = resolveAssets(project, ext)
            test.systemProperty("hytest.serverJar", serverJar.absolutePath)
            if (assets.isFile) {
                test.systemProperty("hytest.assets", assets.absolutePath)
            }
            test.doFirst {
                require(serverJar.isFile) {
                    "HytaleServer.jar not found. Run './gradlew runServer' once to provision the " +
                        "server, or configure hytest { serverJar } / serverDir. " +
                        "Searched: ${serverJar.absolutePath}"
                }
            }
            test.systemProperty("hytest.pluginJar",
                mergedJar.get().archiveFile.get().asFile.absolutePath)
        }
    }

    private fun resolveServerJar(project: Project, ext: HytestExtension): File =
        if (ext.serverJar.isPresent) project.file(ext.serverJar.get())
        else ext.serverDir.file("HytaleServer.jar").get().asFile

    private fun resolveAssets(project: Project, ext: HytestExtension): File =
        if (ext.assets.isPresent) project.file(ext.assets.get())
        else ext.serverDir.file("Assets.zip").get().asFile

    private fun applyHygradleDefaults(project: Project, ext: HytestExtension) {
        val hytale = project.extensions.findByName("hytale") ?: return
        try {
            val server = hytale.javaClass.getMethod("getServer").invoke(hytale)
            val serverDir = server.javaClass.getMethod("getServerDir").invoke(server)
            if (serverDir is DirectoryProperty) {
                ext.serverDir.convention(serverDir)
            }
        } catch (_: Exception) {
            // hygradle present but shape differs — leave defaults
        }
    }

    private fun resolveJunitPlatformVersion(project: Project): String =
        project.findProperty("junitPlatformVersion")?.toString() ?: "6.1.1"

    companion object {
        const val SOURCE_SET = "hytaleTest"
    }
}
