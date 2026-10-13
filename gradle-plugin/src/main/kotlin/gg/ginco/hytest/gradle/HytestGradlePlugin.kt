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

        val runtimePlugin = project.configurations.create("hytestRuntimePlugin") { config ->
            config.setCanBeConsumed(false)
            config.setCanBeResolved(true)
            config.description = "hytest-runtime jar merged into the deployed plugin"
        }

        project.dependencies.add(hytaleTest.runtimeOnlyConfigurationName,
            "org.junit.platform:junit-platform-launcher:${resolveJunitPlatformVersion(project)}")

        // The three hytest coordinates are declared after project evaluation:
        // for `plugins { }` consumers apply() runs *before* the build-script
        // body, so reading ext.version here would bake the convention default
        // into the notation strings — a later `hytest { version = … }`
        // assignment would then never reach artifact resolution (only the
        // merged jar's manifest, which reads the live property). Moving the
        // declarations into afterEvaluate makes the extension the single
        // source of truth for both. Safe spot: nothing resolves these
        // configurations before the task graph is requested.
        project.afterEvaluate {
            val hytestVersion = ext.version.get()
            project.dependencies.add(runtimePlugin.name, "gg.ginco.hytest:runtime:$hytestVersion")
            project.dependencies.add(hytaleTest.implementationConfigurationName,
                "gg.ginco.hytest:runtime:$hytestVersion")
            project.dependencies.add(hytaleTest.runtimeOnlyConfigurationName,
                "gg.ginco.hytest:junit5:$hytestVersion")
        }

        val manifestTask = registerHytestManifestTask(project)
        val mergedJar = registerMergedJarTask(project, runtimePlugin, hytaleTest, main, manifestTask)
        configurePluginUnderTestManifest(project, manifestTask)
        registerTestTask(project, ext, sourceSets, mergedJar)
        wireServerProvisioning(project)
    }

    /**
     * When hygradle is applied, make `hytaleTest` depend on its `provisionServer`
     * task so a clean checkout downloads the server files before testing. Standalone
     * hytest (no hygradle) is untouched, and hygradle versions without the task
     * (e.g. 0.4.0) keep the explicit error message.
     */
    private fun wireServerProvisioning(project: Project) {
        val hytaleTest = project.tasks.named("hytaleTest", Test::class.java)
        project.pluginManager.withPlugin("gg.ginco.hygradle") {
            project.afterEvaluate {
                val provision = project.tasks.findByName("provisionServer") ?: return@afterEvaluate
                hytaleTest.configure { it.dependsOn(provision) }
            }
        }
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
        project: Project,
        runtimePlugin: Configuration,
        hytaleTest: SourceSet,
        main: SourceSet,
        manifestTask: TaskProvider<HytestManifestTask>,
    ): TaskProvider<Jar> =
        project.tasks.register("createHytaleTestPlugin", Jar::class.java) { jar ->
            jar.description = "Builds the merged Hytest plugin jar (runtime + hytaleTest classes) for server deployment."
            jar.archiveBaseName.set("Hytest")
            jar.archiveClassifier.set("")
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            // The provider below loses build dependencies (zipTree of resolved
            // files is not Buildable), so wire the runtime jar explicitly —
            // otherwise this task runs before runtime/build/libs/*.jar exists
            // on a clean checkout and fails with "Cannot expand ZIP".
            jar.dependsOn(runtimePlugin)
            // The generated manifest must be added FIRST: with EXCLUDE the
            // first occurrence of a duplicate wins, and manifest.json also
            // ships inside the runtime jar. Whose manifest this is decides
            // which plugin Main the server instantiates — see
            // [configurePluginUnderTestManifest].
            jar.from(manifestTask)
            jar.from(project.provider {
                runtimePlugin.files.filter { it.name.endsWith(".jar") }.map { project.zipTree(it) } as Iterable<*>
            })
            // The plugin under test's runtime dependencies (JSON libs, codec
            // runtimes, stdlib, ...) must ship in the merged jar: on the
            // server only this jar's classloader exists. Mirrors hygradle's
            // bundleDependencies behaviour for normal plugin jars.
            val runtimeClasspath = project.configurations.getByName(
                main.runtimeClasspathConfigurationName)
            jar.dependsOn(runtimeClasspath)
            jar.from(project.provider {
                runtimeClasspath.files
                    .filter { it.name.endsWith(".jar") && !it.name.startsWith("hytest") }
                    .map { project.zipTree(it) } as Iterable<*>
            })
            jar.from(hytaleTest.output)
            jar.from(main.output.classesDirs)
        }

    /**
     * Registers the [HytestManifestTask] with hytest's own identity as
     * defaults (legacy single-plugin mode: the merged jar is a hytest plugin).
     */
    private fun registerHytestManifestTask(project: Project): TaskProvider<HytestManifestTask> {
        val ext = project.extensions.getByType(HytestExtension::class.java)
        return project.tasks.register("hytestPluginManifest", HytestManifestTask::class.java) { task ->
            task.group = "hytale"
            task.description = "Generates the merged Hytest jar's manifest.json"
            task.hytestVersion.set(ext.version)
            task.pluginGroup.set("Ginco")
            task.pluginName.set("Hytest")
            task.pluginVersion.set(ext.version)
            task.pluginMainClass.set("gg.ginco.hytest.runtime.HytestPlugin")
            task.manifestFile.set(project.layout.buildDirectory.file("generated/hytest/manifest.json"))
        }
    }

    /**
     * With hygradle the merged jar ships the plugin under test's identity
     * (Main = the real plugin) with hytest attached as a sub-plugin, so the
     * server loads BOTH from one jar/classloader: the plugin under test gets
     * its real lifecycle and test classes share its class identity. Without
     * hygradle (or with an incomplete `hytale { }` block) the defaults stay
     * and the jar remains a plain hytest plugin.
     */
    private fun configurePluginUnderTestManifest(project: Project, manifestTask: TaskProvider<HytestManifestTask>) {
        project.pluginManager.withPlugin("gg.ginco.hygradle") {
            project.afterEvaluate {
                val hytale = project.extensions.findByName("hytale") ?: return@afterEvaluate
                fun prop(name: String): String? = try {
                    @Suppress("UNCHECKED_CAST")
                    (hytale.javaClass.getMethod(name).invoke(hytale) as org.gradle.api.provider.Property<String>).orNull
                } catch (_: Exception) { null }

                val group = prop("getGroup")
                val name = prop("getName")
                val mainClass = prop("getMainClass")
                if (group.isNullOrBlank() || name.isNullOrBlank() || mainClass.isNullOrBlank()) {
                    project.logger.warn(
                        "hytest: hygradle is applied but hytale { group / name / mainClass } is incomplete — " +
                        "the Hytest test jar will NOT load the plugin under test (legacy single-plugin mode).")
                    return@afterEvaluate
                }
                manifestTask.configure { task ->
                    task.pluginGroup.set(group)
                    task.pluginName.set(name)
                    task.pluginVersion.set(project.version.toString())
                    task.pluginMainClass.set(mainClass)
                    prop("getDescription")?.let { task.pluginDescription.set(it) }
                    prop("getServerVersion")?.let { task.pluginServerVersion.set(it) }
                }
            }
        }
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

            // Captured here (task realization, after project evaluation) so user
            // hytest { } / hytale { server { } } config wins. Only serializable
            // locals are captured into execution-time lambdas below.
            val serverDir = ext.serverDir.get().asFile
            val explicitServerJar = ext.serverJar.orNull?.let { project.file(it) }
            val explicitAssets = ext.assets.orNull?.let { project.file(it) }
            val gradleUserHome = project.gradle.gradleUserHomeDir

            test.systemProperty("hytest.serverDir", serverDir.absolutePath)
            // JSON list so an arg containing spaces survives the engine-side
            // parse; ServerProcess.splitArgs still accepts the legacy bare form.
            test.systemProperty("hytest.jvmArgs", jvmArgsToJson(ext.jvmArgs.get()))
            test.systemProperty("hytest.timeoutSeconds", ext.timeoutSeconds.get().toString())
            test.systemProperty("hytest.firstFrameGraceSeconds", ext.firstFrameGraceSeconds.get().toString())
            test.systemProperty("hytest.showServerLogs", ext.showServerLogs.get().toString())
            test.systemProperty("hytest.extraMods",
                ext.extraMods.get().joinToString(java.io.File.pathSeparator) { it.toString() })
            if (ext.javaExecutable.isPresent) {
                test.systemProperty("hytest.java", ext.javaExecutable.get())
            }

            // Resolved in doFirst — after provisionServer (a task dependency) has
            // run, but before the task action spawns the test worker. Must stay a
            // plain string: Test's systemProperties map stringifies Providers.
            test.doFirst {
                val serverJar = resolveServerJar(serverDir, explicitServerJar, gradleUserHome)
                require(serverJar.isFile) {
                    "HytaleServer.jar not found. Run './gradlew provisionServer' (requires the " +
                        "'gg.ginco.hygradle' plugin) to download the server files, or configure " +
                        "hytest { serverJar } / serverDir. " +
                        "Searched: ${serverDir.absolutePath}"
                }
                test.systemProperty("hytest.serverJar", serverJar.absolutePath)
                resolveAssets(serverDir, explicitAssets, gradleUserHome)?.let {
                    test.systemProperty("hytest.assets", it.absolutePath)
                }
            }
            test.systemProperty("hytest.pluginJar",
                mergedJar.get().archiveFile.get().asFile.absolutePath)
            // showServerLogs forwards server output to the TEST JVM's stdout —
            // which Gradle then captures and hides unless told otherwise. With
            // both on, CI logs contain the full server + per-test timeline.
            test.testLogging { logging ->
                logging.showStandardStreams = ext.showServerLogs.get()
            }
        }
    }

    /**
     * Resolution order: explicit `hytest { serverJar }`, then the server dir
     * (hygradle's run dir), then hygradle's per-version download cache as
     * populated by its `provisionServer` task. If nothing exists yet, the
     * default run-dir location is returned so error messages point at it.
     */
    private fun resolveServerJar(serverDir: File, explicit: File?, gradleUserHome: File): File =
        explicit ?: hygradleServerFile(serverDir, gradleUserHome, "HytaleServer.jar")
            ?: File(serverDir, "HytaleServer.jar")

    private fun resolveAssets(serverDir: File, explicit: File?, gradleUserHome: File): File? =
        explicit?.takeIf { it.isFile }
            ?: hygradleServerFile(serverDir, gradleUserHome, "Assets.zip")

    private fun hygradleServerFile(serverDir: File, gradleUserHome: File, name: String): File? {
        File(serverDir, name).takeIf { it.isFile }?.let { return it }
        val marker = File(serverDir, ".hygradle-version")
        if (!marker.isFile) return null
        val version = marker.readText().trim()
        if (version.isEmpty()) return null
        return File(gradleUserHome, "caches/hygradle/server/$version/$name").takeIf { it.isFile }
    }

    /** Minimal JSON array writer — the build script shares no util with the junit5 artifact. */
    private fun jvmArgsToJson(args: List<String>): String =
        args.joinToString(",", prefix = "[", postfix = "]") { arg ->
            "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

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

    /**
     * Fallback pin for junit5 artifacts that predate shipping a matched
     * junit-platform-launcher as an api dependency. Newer artifacts resolve
     * their own launcher transitively (Gradle picks the higher version), so
     * this stops being load-bearing once those are published. Explicit
     * override: `junitPlatformVersion` gradle property.
     */
    private fun resolveJunitPlatformVersion(project: Project): String =
        project.findProperty("junitPlatformVersion")?.toString() ?: "6.1.1"

    companion object {
        const val SOURCE_SET = "hytaleTest"
    }
}
