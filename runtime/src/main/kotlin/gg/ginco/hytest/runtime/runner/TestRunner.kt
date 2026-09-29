package gg.ginco.hytest.runtime.runner

import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.ShutdownReason
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.discover.ClasspathTestScanner
import gg.ginco.hytest.runtime.report.ResultReporter
import gg.ginco.hytest.runtime.world.TestWorldFactory
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

object TestRunner {

    private val bodyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hytest-test-body").apply { isDaemon = true }
    }

    // Tests this run still owes a verdict for, drained as each reports.
    // Lets a torn-down run name exactly which tests were abandoned.
    private val pendingVerdicts = Collections.synchronizedSet(LinkedHashSet<TestDescriptor>())

    fun runAsync() {
        val loader = TestRunner::class.java.classLoader
        val tests = ClasspathTestScanner.discover(loader)
        if (tests.isEmpty()) {
            ResultReporter.warning("no @HytaleTest methods found — ensure tests are in src/hytaleTest/java/")
        }
        ResultReporter.runStarted(tests.size)

        pendingVerdicts.addAll(tests)

        var chain = CompletableFuture.completedFuture<Void>(null)
        val passed = intArrayOf(0)
        val failed = intArrayOf(0)

        for (desc in tests) {
            chain = chain.thenCompose { runOne(desc) }
                .whenComplete { ok, _ ->
                    if (ok == true) passed[0]++ else failed[0]++
                }
                .thenApply { null }
        }

        val runReported = AtomicBoolean(false)
        // A server killed mid-suite still runs JVM shutdown hooks; use that
        // window to report the abandoned tests as aborted (a FAILURE on the
        // engine side) instead of leaving them as silent never-rans.
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                if (runReported.getAndSet(true)) return@Thread
                val abandoned = synchronized(pendingVerdicts) { pendingVerdicts.toList() }
                for (desc in abandoned) {
                    ResultReporter.testAborted(desc.testClass.name, desc.method.name,
                        "server shut down before this test ran — the test run was torn down mid-flight")
                }
                ResultReporter.runFinished(tests.size, passed[0],
                    failed[0] + abandoned.size, if (abandoned.isEmpty() && failed[0] == 0) 0 else 1)
            } catch (_: Throwable) {
                // best-effort: stdout may already be gone during shutdown
            }
        }, "hytest-shutdown-report"))

        chain.whenComplete { _, ex ->
            if (runReported.getAndSet(true)) return@whenComplete
            val total = passed[0] + failed[0]
            val exitCode = if (failed[0] == 0) 0 else 1
            ResultReporter.runFinished(total, passed[0], failed[0], exitCode)
            bodyExecutor.shutdownNow()
            try {
                HytaleServer.get().shutdownServer(ShutdownReason(exitCode, "hytest"))
            } catch (_: Throwable) {
                // the process is already dying
            }
        }
    }

    private fun runOne(desc: TestDescriptor): CompletableFuture<Boolean> {
        ResultReporter.testStarted(desc.testClass.name, desc.method.name)
        val start = System.currentTimeMillis()

        val instance = try {
            desc.testClass.getDeclaredConstructor().newInstance().also { inst ->
                for (m in desc.beforeEach) m.isAccessible = true
                for (m in desc.afterEach) m.isAccessible = true
            }
        } catch (e: Exception) { report(desc, start, false, e); return CompletableFuture.completedFuture(false) }

        try {
            invokeLifecycleMethods(instance, desc.beforeEach)
        } catch (e: Exception) {
            report(desc, start, false, e)
            invokeAfterEach(instance, desc.afterEach)
            return CompletableFuture.completedFuture(false)
        }

        return TestWorldFactory.createVoid()
            .thenCompose { world ->
                val helper = HytaleTestHelper(world)
                val body = CompletableFuture.runAsync({
                    try {
                        desc.method.let { it.isAccessible = true; it.invoke(instance, helper) }
                    } catch (t: Throwable) {
                        val cause = if (t is InvocationTargetException) t.cause ?: t else t
                        helper.fail(cause)
                    }
                }, bodyExecutor)

                body.thenCompose { helper.doneFuture() }
                    .orTimeout(desc.timeoutSeconds, TimeUnit.SECONDS)
                    .handle { _, ex ->
                        var error = unwrap(ex)
                        var passed = error == null && helper.failure() == null
                        if (!passed && error == null) error = helper.failure()
                        if (error is TimeoutException)
                            error = AssertionError("test timed out after ${desc.timeoutSeconds}s")
                        invokeAfterEach(instance, desc.afterEach)
                        report(desc, start, passed, error)
                        TestWorldFactory.dispose(world)
                        passed
                    }
            }
            .exceptionally { ex ->
                val err = unwrap(ex)
                invokeAfterEach(instance, desc.afterEach)
                report(desc, start, false, err)
                false
            }
    }

    private fun invokeLifecycleMethods(instance: Any, methods: List<java.lang.reflect.Method>) {
        for (m in methods) {
            try { m.invoke(instance) }
            catch (t: Throwable) {
                val cause = if (t is InvocationTargetException) t.cause ?: t else t
                throw if (cause is Exception) cause else RuntimeException(cause)
            }
        }
    }

    private fun invokeAfterEach(instance: Any, methods: List<java.lang.reflect.Method>) {
        for (m in methods) {
            try { m.invoke(instance) } catch (_: Throwable) { /* best-effort */ }
        }
    }

    private fun report(desc: TestDescriptor, start: Long, passed: Boolean, error: Throwable?) {
        pendingVerdicts.remove(desc)
        val duration = System.currentTimeMillis() - start
        // Human-readable progress on the server's own stdout: forwarded by
        // showServerLogs, so slow CI runs can be attributed per test.
        println("[hytest] ${desc.testClass.simpleName}.${desc.method.name} " +
            (if (passed) "PASSED" else "FAILED") + " in ${duration}ms" +
            (error?.let { " — ${it.message?.take(120)}" } ?: ""))
        ResultReporter.testFinished(
            desc.testClass.name, desc.method.name, passed,
            duration, error)
    }

    private fun unwrap(t: Throwable?): Throwable? =
        if (t is CompletionException && t.cause != null) t.cause else t
}
