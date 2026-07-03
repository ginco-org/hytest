package gg.ginco.hytest.junit5.execution

import gg.ginco.hytest.junit5.descriptor.ClassDescriptor
import gg.ginco.hytest.junit5.descriptor.MethodDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestExecutionResult

object ServerProcess {

    private val EOF = Frame("eof", null, null, null, 0L, null, null)
    private const val OUTPUT_RING_LINES = 300

    class MethodDescriptorRef(
        val descriptor: MethodDescriptor,
        val classRef: ClassDescriptorRef
    )

    class ClassDescriptorRef(val descriptor: ClassDescriptor) {
        fun beginIfFirst() = descriptor.beginIfFirst()
        fun methodFinished() { descriptor.methodFinished() }
        fun allMethodsFinished() = descriptor.allMethodsFinished()
    }

    fun run(
        methods: Map<String, MethodDescriptorRef>,
        listener: EngineExecutionListener,
        showServerLogs: Boolean
    ) {
        val config = Config.fromSystemProperties()
        val process = startServer(config)
        val queue = LinkedBlockingQueue<Frame>()
        val serverOutput = ArrayDeque<String>(OUTPUT_RING_LINES + 32)

        val stdout = Thread({ readFrames(process.inputStream, queue, serverOutput, showServerLogs) }, "hytest-stdout")
        stdout.isDaemon = true; stdout.start()

        val stderr = Thread({ drain(process.errorStream, serverOutput, showServerLogs) }, "hytest-stderr")
        stderr.isDaemon = true; stderr.start()

        val deadline = System.currentTimeMillis() + config.timeoutMillis
        try {
            dispatch(methods, listener, queue, serverOutput, deadline)
        } finally {
            ensureTerminated(process, deadline)
        }
    }

    private fun dispatch(
        methods: Map<String, MethodDescriptorRef>,
        listener: EngineExecutionListener,
        queue: LinkedBlockingQueue<Frame>,
        serverOutput: ArrayDeque<String>,
        deadline: Long
    ) {
        val startedKeys = linkedSetOf<String>()
        val finishedKeys = linkedSetOf<String>()
        val startedClasses = linkedSetOf<ClassDescriptorRef>()
        var runFinished = false

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = try {
                queue.poll(remaining, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
            if (frame == null) continue
            if (frame === EOF) break
            when (frame.type) {
                "test_started" -> startTest(frame, methods, listener, startedKeys, startedClasses)
                "test_finished" -> finishTest(frame, methods, listener, finishedKeys, startedClasses)
                "run_finished" -> runFinished = true
                "warning" -> System.err.println("[hytest] ${frame.message}")
            }
            if (runFinished) break
        }

        if (!runFinished) {
            synchronized(serverOutput) {
                if (serverOutput.isNotEmpty()) {
                    System.err.println("=== Hytale server output (tail) ===")
                    for (line in serverOutput) System.err.println(line)
                    System.err.println("=== end of server output ===")
                }
            }
        }

        for (ref in methods.values) {
            if (ref.descriptor.key() in finishedKeys) continue
            startTestMethod(ref, listener, startedKeys, startedClasses)
            listener.executionFinished(ref.descriptor,
                TestExecutionResult.aborted(AssertionError(
                    "server exited without reporting a result for ${ref.descriptor.className}.${ref.descriptor.methodName}")))
        }
        for (classRef in startedClasses) {
            listener.executionFinished(classRef.descriptor, TestExecutionResult.successful())
        }
    }

    private fun startTestMethod(
        ref: MethodDescriptorRef, listener: EngineExecutionListener,
        startedKeys: MutableSet<String>, startedClasses: MutableSet<ClassDescriptorRef>
    ) {
        if (!startedKeys.add(ref.descriptor.key())) return
        if (ref.classRef.beginIfFirst()) {
            listener.executionStarted(ref.classRef.descriptor)
            startedClasses.add(ref.classRef)
        }
        listener.executionStarted(ref.descriptor)
    }

    private fun startTest(
        frame: Frame, methods: Map<String, MethodDescriptorRef>,
        listener: EngineExecutionListener, startedKeys: MutableSet<String>,
        startedClasses: MutableSet<ClassDescriptorRef>
    ) {
        val ref = methods[frame.testKey()] ?: return
        startTestMethod(ref, listener, startedKeys, startedClasses)
    }

    private fun finishTest(
        frame: Frame, methods: Map<String, MethodDescriptorRef>,
        listener: EngineExecutionListener, finishedKeys: MutableSet<String>,
        startedClasses: MutableSet<ClassDescriptorRef>
    ) {
        val ref = methods[frame.testKey()] ?: return
        if (!finishedKeys.add(frame.testKey())) return
        val result = if (frame.status == "passed") TestExecutionResult.successful()
        else TestExecutionResult.failed(AssertionError(frame.error))
        listener.executionFinished(ref.descriptor, result)

        ref.classRef.methodFinished()
        if (ref.classRef.allMethodsFinished() && startedClasses.remove(ref.classRef)) {
            listener.executionFinished(ref.classRef.descriptor, TestExecutionResult.successful())
        }
    }

    private fun startServer(config: Config): Process {
        val workDir = config.workDir
        Files.createDirectories(workDir)
        val modsDir = workDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.copy(config.pluginJar, modsDir.resolve("Hytest.jar"), StandardCopyOption.REPLACE_EXISTING)

        val command = buildList {
            add(config.java)
            addAll(config.jvmArgs)
            add("-Dhytest.run=true")
            add("-jar"); add(config.serverJar.toString())
            if (config.assets != null) { add("--assets"); add(config.assets.toString()) }
            add("--disable-sentry")
            add("--disable-file-watcher")
        }
        val builder = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
        return builder.start()
    }

    private fun readFrames(
        stream: java.io.InputStream, queue: LinkedBlockingQueue<Frame>,
        serverOutput: ArrayDeque<String>, showServerLogs: Boolean
    ) {
        try { BufferedReader(InputStreamReader(stream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (Frame.isFrame(line)) {
                    try { queue.put(Frame.parse(line)) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); return
                    }
                } else {
                    appendLine(serverOutput, line)
                    if (showServerLogs) System.out.println("[server] $line")
                }
                line = reader.readLine()
            }
        }} catch (_: Exception) { /* stream closed */ }
        finally { try { queue.put(EOF) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }}
    }

    private fun drain(stream: java.io.InputStream, serverOutput: ArrayDeque<String>, showServerLogs: Boolean) {
        try { BufferedReader(InputStreamReader(stream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                appendLine(serverOutput, line)
                if (showServerLogs) System.err.println("[server:err] $line")
                line = reader.readLine()
            }
        }} catch (_: Exception) { /* stream closed */ }
    }

    private fun appendLine(output: ArrayDeque<String>, line: String) {
        synchronized(output) {
            output.addLast(line)
            while (output.size > OUTPUT_RING_LINES) output.removeFirst()
        }
    }

    private fun ensureTerminated(process: Process, deadline: Long) {
        val remaining = maxOf(1L, deadline - System.currentTimeMillis())
        try {
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    data class Config(
        val serverJar: Path,
        val assets: Path?,
        val pluginJar: Path,
        val workDir: Path,
        val java: String,
        val jvmArgs: List<String>,
        val timeoutMillis: Long,
        val showServerLogs: Boolean
    ) {
        companion object {
            fun fromSystemProperties(): Config {
                val serverJar = requirePath("hytest.serverJar")
                val pluginJar = requirePath("hytest.pluginJar")
                val assetsRaw = System.getProperty("hytest.assets")
                val assets = if (assetsRaw.isNullOrBlank()) null else Path.of(assetsRaw)
                val workDirRaw = System.getProperty("hytest.serverDir")
                val workDir = try {
                    val parent = if (workDirRaw.isNullOrBlank()) Path.of(System.getProperty("java.io.tmpdir"))
                    else Path.of(workDirRaw)
                    Files.createTempDirectory(parent, "hytest-run-")
                } catch (e: Exception) {
                    throw IllegalStateException("Could not create temp server dir", e)
                }
                val java = System.getProperty("hytest.java",
                    Path.of(System.getProperty("java.home"), "bin", "java").toString())
                val jvmArgs = splitArgs(System.getProperty("hytest.jvmArgs", "-Xmx4G -Xms1G"))
                val timeoutSeconds = System.getProperty("hytest.timeoutSeconds", "300").toLong()
                val showServerLogs = System.getProperty("hytest.showServerLogs", "false").toBoolean()
                return Config(serverJar, assets, pluginJar, workDir, java, jvmArgs,
                    timeoutSeconds * 1000L, showServerLogs)
            }

            private fun requirePath(key: String): Path {
                val value = System.getProperty(key)
                require(!value.isNullOrBlank()) {
                    "Missing required system property $key — the hytest Gradle plugin must set it."
                }
                return Path.of(value)
            }

            private fun splitArgs(args: String): List<String> =
                args.split("\\s+".toRegex()).filter { it.isNotBlank() }
        }
    }
}
