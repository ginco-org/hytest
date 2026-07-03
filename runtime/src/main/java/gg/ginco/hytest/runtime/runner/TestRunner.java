package gg.ginco.hytest.runtime.runner;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ShutdownReason;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;
import gg.ginco.hytest.runtime.discover.ClasspathTestScanner;
import gg.ginco.hytest.runtime.report.ResultReporter;
import gg.ginco.hytest.runtime.world.TestWorldFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers tests, runs them sequentially (each in its own ephemeral world),
 * reports results, and shuts the server down with an exit code derived from the
 * failure count. Fully asynchronous; safe to call from the boot thread.
 */
public final class TestRunner {

    private static final ExecutorService BODY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hytest-test-body");
        t.setDaemon(true);
        return t;
    });

    private TestRunner() {
    }

    public static void runAsync() {
        ClassLoader loader = TestRunner.class.getClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            ResultReporter.warning("hytest runtime classloader is not a URLClassLoader; test discovery will produce no results. "
                    + "Got: " + loader.getClass().getName());
        }
        List<TestDescriptor> tests = ClasspathTestScanner.discover(loader);
        if (tests.isEmpty()) {
            ResultReporter.warning("no @HytaleTest methods found — ensure tests are in src/hytaleTest/java/");
        }
        ResultReporter.runStarted(tests.size());

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        int[] passed = {0};
        int[] failed = {0};

        for (TestDescriptor descriptor : tests) {
            chain = chain.thenCompose(x -> runOne(descriptor)
                    .whenComplete((ok, ignored) -> {
                        if (Boolean.TRUE.equals(ok)) {
                            passed[0]++;
                        } else {
                            failed[0]++;
                        }
                    })
                    .thenApply(ok -> (Void) null));
        }

        AtomicBoolean runReported = new AtomicBoolean(false);
        chain.whenComplete((v, ex) -> {
            if (runReported.getAndSet(true)) {
                return; // shutdownServer threw an earlier time — we already reported
            }
            int total = passed[0] + failed[0];
            int exitCode = failed[0] == 0 ? 0 : 1;
            ResultReporter.runFinished(total, passed[0], failed[0], exitCode);
            BODY_EXECUTOR.shutdownNow();
            try {
                HytaleServer.get().shutdownServer(new ShutdownReason(exitCode, "hytest"));
            } catch (Throwable ignored) {
                // best-effort — the process is already dying
            }
        });
    }

    private static CompletableFuture<Boolean> runOne(TestDescriptor descriptor) {
        ResultReporter.testStarted(descriptor.testClass().getName(), descriptor.method().getName());
        long start = System.currentTimeMillis();

        Object instance;
        try {
            instance = descriptor.testClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            report(descriptor, start, false, e);
            return CompletableFuture.completedFuture(false);
        }

        return TestWorldFactory.createVoid()
                .thenCompose(world -> {
                    HytaleTestHelper helper = new HytaleTestHelper(world);
                    // Run the test body off the world thread so a test may block without
                    // deadlocking the world. Use HytaleTestHelper#runOnWorld for logic
                    // that must execute on the world thread.
                    CompletableFuture<Void> body = CompletableFuture.runAsync(() -> {
                        try {
                            descriptor.method().setAccessible(true);
                            descriptor.method().invoke(instance, helper);
                        } catch (Throwable t) {
                            Throwable cause = (t instanceof InvocationTargetException ite && ite.getCause() != null)
                                    ? ite.getCause() : t;
                            helper.fail(cause);
                        }
                    }, BODY_EXECUTOR);

                    return body
                            .thenCompose(x -> helper.doneFuture())
                            .orTimeout(descriptor.timeoutSeconds(), TimeUnit.SECONDS)
                            .handle((ignored, ex) -> {
                                Throwable error = unwrap(ex);
                                boolean passed = error == null && helper.failure() == null;
                                if (!passed && error == null) {
                                    error = helper.failure();
                                }
                                if (error instanceof TimeoutException) {
                                    error = new AssertionError("test timed out after "
                                            + descriptor.timeoutSeconds() + "s");
                                }
                                report(descriptor, start, passed, error);
                                TestWorldFactory.dispose(world);
                                return passed;
                            });
                })
                .exceptionally(ex -> {
                    Throwable err = unwrap(ex);
                    report(descriptor, start, false, err);
                    return false;
                });
    }

    private static void report(TestDescriptor descriptor, long start, boolean passed, Throwable error) {
        ResultReporter.testFinished(
                descriptor.testClass().getName(),
                descriptor.method().getName(),
                passed,
                System.currentTimeMillis() - start,
                error);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return throwable;
    }
}
