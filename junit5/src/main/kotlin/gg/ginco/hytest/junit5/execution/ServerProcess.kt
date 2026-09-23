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

    // How long a server that dispatch no longer waits for may take to exit on
    // its own before it is destroyed. The healthy path (post-run_finished
    // shutdown) fits comfortably; only a hang pays the full grace.
    private const val SHUTDOWN_GRACE_MILLIS = 15_000L

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
    ): RunOutcome {
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
            return dispatch(methods, listener, queue, serverOutput, deadline,
                config.timeoutMillis, config.firstFrameGraceMillis)
        } finally {
            // Kill deadline measured from when dispatch gave up, not from the
            // run budget: a first-frame abort must destroy a still-idling
            // (never-loaded) server within seconds instead of riding the rest
            // of the 300s budget.
            ensureTerminated(process,
                minOf(deadline, System.currentTimeMillis() + SHUTDOWN_GRACE_MILLIS))
        }
    }

    /**
     * How the run actually ended, so [HytestEngine] can finish its root with a
     * result matching reality. A run that lost tests to a dead server or an
     * expired budget must surface as a failure — never as a green build with
     * quiet skips.
     */
    class RunOutcome(
        private val failedTests: Int,
        private val unfinishedTests: Int,
        private val endSummary: String?,
        // Leads the root message when set: counts are downstream symptoms of
        // a server that never loaded the test jar, and the diagnosis is what
        // the user acts on.
        private val pluginNeverResponded: Boolean = false
    ) {
        fun rootResult(): TestExecutionResult {
            if (failedTests == 0 && unfinishedTests == 0 && endSummary == null && !pluginNeverResponded) {
                return TestExecutionResult.successful()
            }
            val parts = ArrayList<String>(4)
            if (pluginNeverResponded) parts.add(endSummary ?: "the test plugin never responded")
            if (failedTests > 0) parts.add("$failedTests test(s) failed")
            if (unfinishedTests > 0) parts.add("$unfinishedTests discovered test(s) never finished")
            if (endSummary != null && !pluginNeverResponded) parts.add(endSummary)
            return TestExecutionResult.failed(
                AssertionError("Hytale test run failed: ${parts.joinToString("; ")}"))
        }
    }

    private fun dispatch(
        methods: Map<String, MethodDescriptorRef>,
        listener: EngineExecutionListener,
        queue: LinkedBlockingQueue<Frame>,
        serverOutput: ArrayDeque<String>,
        deadline: Long,
        runBudgetMillis: Long,
        firstFrameGraceMillis: Long
    ): RunOutcome {
        val startedKeys = linkedSetOf<String>()
        val finishedKeys = linkedSetOf<String>()
        val startedClasses = linkedSetOf<ClassDescriptorRef>()
        var runFinished = false
        var serverDied = false
        var testFailures = 0
        // Failures the server told us about for tests this engine never
        // discovered — invisible as leaf results, so they must reach the root.
        val unknownFailures = linkedSetOf<String>()
        var runExitCode: Int? = null
        var runReportedFailures: Int? = null
        // A healthy plugin announces itself within seconds of the server
        // finishing boot; silence past the grace window means the jar was
        // rejected (bad manifest, load error) and waiting out the whole run
        // budget buys nothing. Strictly a first-frame check — mid-run stalls
        // keep the existing budget semantics.
        val firstFrameDeadline =
            minOf(deadline, deadline - runBudgetMillis + firstFrameGraceMillis)
        var sawAnyFrame = false
        var pluginNeverResponded = false

        while (true) {
            val waitLimit = if (sawAnyFrame) deadline else firstFrameDeadline
            val remaining = waitLimit - System.currentTimeMillis()
            if (remaining <= 0) {
                if (!sawAnyFrame) pluginNeverResponded = true
                break
            }
            val frame = try {
                queue.poll(remaining, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
            if (frame == null) continue
            if (frame === EOF) { serverDied = true; break }
            sawAnyFrame = true
            when (frame.type) {
                "test_started" -> startTest(frame, methods, listener, startedKeys, startedClasses)
                "test_finished" -> {
                    val ref = methods[frame.testKey()]
                    if (ref == null) {
                        if (frame.status != "passed") unknownFailures.add(frame.testKey())
                    } else if (finishTest(frame, ref, listener, finishedKeys, startedClasses)) {
                        testFailures++
                    }
                }
                "run_finished" -> {
                    runFinished = true
                    runExitCode = frame.exitCode
                    runReportedFailures = frame.failedCount
                }
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

        // A discovered test with no reported verdict is a FAILURE, not a skip:
        // Gradle renders aborted/skipped as green, and "the plugin never loaded"
        // must never look like a passing suite.
        val pluginSilentSummary = "no response from the test plugin within " +
            "${firstFrameGraceMillis / 1000}s of server start — the test jar was likely rejected; " +
            "re-run with showServerLogs"
        val unfinishedReason = when {
            pluginNeverResponded -> pluginSilentSummary
            serverDied -> "server exited before this test ran — " +
                "the test plugin may have failed to load; check showServerLogs"
            !runFinished -> "run budget of ${runBudgetMillis / 1000}s expired before this test ran — " +
                "the server may have hung or the test plugin may have failed to load; check showServerLogs"
            else -> "the server reported the run finished without ever reporting this test — " +
                "runtime and Gradle-side discovery disagree, or server output was truncated"
        }
        var unfinished = 0
        for (ref in methods.values) {
            if (ref.descriptor.key() in finishedKeys) continue
            unfinished++
            startTestMethod(ref, listener, startedKeys, startedClasses)
            listener.executionFinished(ref.descriptor,
                TestExecutionResult.failed(AssertionError(unfinishedReason)))
        }
        for (classRef in startedClasses) {
            listener.executionFinished(classRef.descriptor, TestExecutionResult.successful())
        }

        // Wire run_finished's verdict through: if the server itself says the
        // run failed but no failed test frame reached this engine, the leaf
        // results cannot fail the build — only the root result can.
        val serverSaidFailed = (runExitCode ?: 0) != 0 || (runReportedFailures ?: 0) > 0
        val exitMismatch = if (runFinished && serverSaidFailed && testFailures == 0 && unfinished == 0) {
            "run_finished reported exitCode=$runExitCode with ${runReportedFailures ?: "?"} failing test(s)" +
                (if (unknownFailures.isNotEmpty())
                    " that the engine never discovered (${unknownFailures.joinToString(", ")})" else "") +
                " — the server-side run failed, so the build fails"
        } else null

        val endSummary = exitMismatch ?: when {
            pluginNeverResponded -> pluginSilentSummary
            unfinished > 0 && serverDied ->
                "server exited before $unfinished test(s) ran — " +
                "the test plugin may have failed to load; check showServerLogs"
            unfinished > 0 && !runFinished ->
                "run budget of ${runBudgetMillis / 1000}s expired with $unfinished test(s) unrun"
            unfinished > 0 ->
                "run finished with $unfinished discovered test(s) never reported"
            else -> null
        }

        return RunOutcome(testFailures, unfinished, endSummary, pluginNeverResponded)
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

    /** Records one finished test; returns true when it finished as a failure. */
    private fun finishTest(
        frame: Frame, ref: MethodDescriptorRef,
        listener: EngineExecutionListener, finishedKeys: MutableSet<String>,
        startedClasses: MutableSet<ClassDescriptorRef>
    ): Boolean {
        if (!finishedKeys.add(frame.testKey())) return false
        // Everything except "passed" — including the runtime's "aborted"
        // status — maps to FAILURE on purpose: TestExecutionResult.aborted()
        // renders as *skipped* in Gradle, and a suite the server abandoned
        // mid-flight must never count as green.
        val result = if (frame.status == "passed") TestExecutionResult.successful()
        else TestExecutionResult.failed(AssertionError(frame.error ?: "test ${frame.status}"))
        listener.executionFinished(ref.descriptor, result)

        ref.classRef.methodFinished()
        if (ref.classRef.allMethodsFinished() && startedClasses.remove(ref.classRef)) {
            listener.executionFinished(ref.classRef.descriptor, TestExecutionResult.successful())
        }
        return frame.status != "passed"
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
            // No session tokens: every supported flow runs the server
            // unauthenticated (GameSession was removed — it was fail-soft and
            // never populated in practice; reintroduce deliberately if a test
            // ever needs account-bound server behaviour).
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
        val firstFrameGraceMillis: Long,
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
                // Generous because the server boots a full JVM (assets, world
                // machinery) before the plugin loads; only true silence is fatal.
                val firstFrameGraceSeconds =
                    System.getProperty("hytest.firstFrameGraceSeconds", "90").toLong()
                val showServerLogs = System.getProperty("hytest.showServerLogs", "false").toBoolean()
                return Config(serverJar, assets, pluginJar, workDir, java, jvmArgs,
                    timeoutSeconds * 1000L, firstFrameGraceSeconds * 1000L, showServerLogs)
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
