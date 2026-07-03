package gg.ginco.hytest.runtime.runner

import com.hypixel.hytale.server.core.HytaleServer
import com.hypixel.hytale.server.core.ShutdownReason
import gg.ginco.hytest.runtime.api.HytaleTestHelper
import gg.ginco.hytest.runtime.discover.ClasspathTestScanner
import gg.ginco.hytest.runtime.report.ResultReporter
import gg.ginco.hytest.runtime.world.TestWorldFactory
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

object TestRunner {

    private val bodyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hytest-test-body").apply { isDaemon = true }
    }

    fun runAsync() {
        val loader = TestRunner::class.java.classLoader
        if (loader !is URLClassLoader) {
            ResultReporter.warning(
                "hytest runtime classloader is not a URLClassLoader; test discovery will produce no results. Got: ${loader.javaClass.name}")
        }
        val tests = ClasspathTestScanner.discover(loader)
        if (tests.isEmpty()) {
            ResultReporter.warning("no @HytaleTest methods found — ensure tests are in src/hytaleTest/java/")
        }
        ResultReporter.runStarted(tests.size)

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

        val instance = try { desc.testClass.getDeclaredConstructor().newInstance() }
        catch (e: Exception) { report(desc, start, false, e); return CompletableFuture.completedFuture(false) }

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
                        report(desc, start, passed, error)
                        TestWorldFactory.dispose(world)
                        passed
                    }
            }
            .exceptionally { ex ->
                val err = unwrap(ex)
                report(desc, start, false, err)
                false
            }
    }

    private fun report(desc: TestDescriptor, start: Long, passed: Boolean, error: Throwable?) {
        ResultReporter.testFinished(
            desc.testClass.name, desc.method.name, passed,
            System.currentTimeMillis() - start, error)
    }

    private fun unwrap(t: Throwable?): Throwable? =
        if (t is CompletionException && t.cause != null) t.cause else t
}
