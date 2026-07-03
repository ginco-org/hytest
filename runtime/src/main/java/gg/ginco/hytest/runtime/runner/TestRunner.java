package gg.ginco.hytest.runtime.runner;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ShutdownReason;
import com.hypixel.hytale.server.core.universe.world.World;
import gg.ginco.hytest.runtime.api.HytaleTestHelper;
import gg.ginco.hytest.runtime.discover.ClasspathTestScanner;
import gg.ginco.hytest.runtime.report.ResultReporter;
import gg.ginco.hytest.runtime.world.TestWorldFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Discovers tests, runs them sequentially (each in its own ephemeral world),
 * reports results, and shuts the server down with an exit code derived from the
 * failure count. Fully asynchronous; safe to call from the boot thread.
 */
public final class TestRunner {

    private TestRunner() {
    }

    public static void runAsync() {
        List<TestDescriptor> tests = ClasspathTestScanner.discover(TestRunner.class.getClassLoader());
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

        chain.whenComplete((v, ex) -> {
            int total = passed[0] + failed[0];
            int exitCode = failed[0] == 0 ? 0 : 1;
            ResultReporter.runFinished(total, passed[0], failed[0], exitCode);
            try {
                HytaleServer.get().shutdownServer(new ShutdownReason(exitCode, "hytest"));
            } catch (Throwable shutdownError) {
                ResultReporter.runFinished(total, passed[0], failed[0], exitCode);
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

        return TestWorldFactory.createVoid().thenCompose(world -> {
            HytaleTestHelper helper = new HytaleTestHelper(world);
            // Run the test body off the world thread so a test may block (e.g. waiting for
            // a simulated player to join) without deadlocking the world, which must keep
            // ticking to complete those joins. Use HytaleTestHelper#runOnWorld for logic
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
            });

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
                            error = new AssertionError("test timed out after " + descriptor.timeoutSeconds() + "s");
                        }
                        report(descriptor, start, passed, error);
                        TestWorldFactory.dispose(world);
                        return passed;
                    });
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
