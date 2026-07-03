package gg.ginco.hytest.junit5.execution;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestExecutionResult;

/**
 * Launches the Hytale server as a subprocess with the merged Hytest plugin in
 * {@code mods/}, then relays the {@code HYTEST} result frames on stdout to the
 * JUnit execution listener.
 *
 * <p>A ring buffer captures the last {@value #OUTPUT_RING_LINES} non-frame
 * lines from the server (both stdout and stderr). If the server exits without
 * sending {@code run_finished}, those lines are dumped to the build console so
 * boot failures are visible even when {@code showServerLogs} is off.
 */
public final class ServerProcess {

    private static final Frame EOF = new Frame("eof", null, null, null, 0L, null, null);
    private static final int OUTPUT_RING_LINES = 300;

    private ServerProcess() {
    }

    public static void run(
            Map<String, MethodDescriptorRef> methods,
            EngineExecutionListener listener,
            boolean showServerLogs) {

        Config config = Config.fromSystemProperties();
        Process process = startServer(config);
        var queue = new LinkedBlockingQueue<Frame>();
        List<String> serverOutput = new ArrayList<>();

        Thread stdout = new Thread(() -> readFrames(process.getInputStream(), queue, serverOutput, showServerLogs),
                "hytest-stdout");
        stdout.setDaemon(true);
        stdout.start();

        Thread stderr = new Thread(() -> drain(process.getErrorStream(), serverOutput, showServerLogs),
                "hytest-stderr");
        stderr.setDaemon(true);
        stderr.start();

        long deadline = System.currentTimeMillis() + config.timeoutMillis();
        try {
            dispatch(methods, listener, queue, serverOutput, deadline);
        } finally {
            ensureTerminated(process, deadline);
        }
    }

    private static void dispatch(
            Map<String, MethodDescriptorRef> methods,
            EngineExecutionListener listener,
            LinkedBlockingQueue<Frame> queue,
            List<String> serverOutput,
            long deadline) {

        Set<String> startedKeys = new LinkedHashSet<>();
        Set<String> finishedKeys = new LinkedHashSet<>();
        Set<ClassDescriptorRef> startedClasses = new LinkedHashSet<>();
        boolean runFinished = false;

        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            Frame frame;
            try {
                frame = queue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (frame == null) {
                continue;
            }
            if (frame == EOF) {
                break; // server exited — run cleanup immediately
            }
            switch (frame.type()) {
                case "test_started" -> startTest(frame, methods, listener, startedKeys, startedClasses);
                case "test_finished" -> finishTest(frame, methods, listener, finishedKeys, startedClasses);
                case "run_finished" -> runFinished = true;
                case "warning" -> System.err.println("[hytest] " + frame.message());
                default -> { /* ignore */ }
            }
            if (runFinished) {
                break;
            }
        }

        if (!runFinished) {
            synchronized (serverOutput) {
                if (!serverOutput.isEmpty()) {
                    System.err.println("=== Hytale server output (tail) ===");
                    for (String line : serverOutput) {
                        System.err.println(line);
                    }
                    System.err.println("=== end of server output ===");
                }
            }
        }

        // Anything the server never reported: emit started then aborted so the
        // JUnit start/finish contract holds.
        for (MethodDescriptorRef ref : methods.values()) {
            if (finishedKeys.contains(ref.descriptor().key())) {
                continue;
            }
            startTestMethod(ref, listener, startedKeys, startedClasses);
            listener.executionFinished(ref.descriptor(),
                    TestExecutionResult.aborted(new AssertionError(
                            "server exited without reporting a result for "
                                    + ref.descriptor().className() + "." + ref.descriptor().methodName())));
        }
        for (ClassDescriptorRef classRef : startedClasses) {
            listener.executionFinished(classRef.descriptor(), TestExecutionResult.successful());
        }
    }

    private static void startTestMethod(
            MethodDescriptorRef ref,
            EngineExecutionListener listener,
            Set<String> startedKeys,
            Set<ClassDescriptorRef> startedClasses) {

        if (!startedKeys.add(ref.descriptor().key())) {
            return; // already started
        }
        if (ref.classRef().beginIfFirst()) {
            listener.executionStarted(ref.classRef().descriptor());
            startedClasses.add(ref.classRef());
        }
        listener.executionStarted(ref.descriptor());
    }

    private static void startTest(
            Frame frame,
            Map<String, MethodDescriptorRef> methods,
            EngineExecutionListener listener,
            Set<String> startedKeys,
            Set<ClassDescriptorRef> startedClasses) {

        MethodDescriptorRef ref = methods.get(frame.testKey());
        if (ref == null) {
            return;
        }
        startTestMethod(ref, listener, startedKeys, startedClasses);
    }

    private static void finishTest(
            Frame frame,
            Map<String, MethodDescriptorRef> methods,
            EngineExecutionListener listener,
            Set<String> finishedKeys,
            Set<ClassDescriptorRef> startedClasses) {

        MethodDescriptorRef ref = methods.get(frame.testKey());
        if (ref == null || !finishedKeys.add(frame.testKey())) {
            return;
        }
        TestExecutionResult result = "passed".equals(frame.status())
                ? TestExecutionResult.successful()
                : TestExecutionResult.failed(new AssertionError(frame.error()));
        listener.executionFinished(ref.descriptor(), result);

        ref.classRef().methodFinished();
        if (ref.classRef().allMethodsFinished() && startedClasses.remove(ref.classRef())) {
            listener.executionFinished(ref.classRef().descriptor(), TestExecutionResult.successful());
        }
    }

    private static Process startServer(Config config) {
        try {
            Path workDir = config.workDir();
            Files.createDirectories(workDir);
            Path modsDir = workDir.resolve("mods");
            Files.createDirectories(modsDir);
            Files.copy(config.pluginJar(), modsDir.resolve("Hytest.jar"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            List<String> command = new ArrayList<>();
            command.add(config.java());
            command.addAll(config.jvmArgs());
            command.add("-Dhytest.run=true");
            command.add("-jar");
            command.add(config.serverJar().toString());
            if (config.assets() != null) {
                command.add("--assets");
                command.add(config.assets().toString());
            }
            command.add("--disable-sentry");
            command.add("--disable-file-watcher");

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false);
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Hytale server: " + e.getMessage(), e);
        }
    }

    private static void readFrames(InputStream stream, LinkedBlockingQueue<Frame> queue,
                                   List<String> serverOutput, boolean showServerLogs) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Frame.isFrame(line)) {
                    try {
                        queue.put(Frame.parse(line));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    appendLine(serverOutput, line);
                    if (showServerLogs) {
                        System.out.println("[server] " + line);
                    }
                }
            }
        } catch (IOException ignored) {
            // process closed stream
        } finally {
            try {
                queue.put(EOF);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void drain(InputStream stream, List<String> serverOutput, boolean showServerLogs) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLine(serverOutput, line);
                if (showServerLogs) {
                    System.err.println("[server:err] " + line);
                }
            }
        } catch (IOException ignored) {
            // process closed stream
        }
    }

    private static void appendLine(List<String> output, String line) {
        synchronized (output) {
            while (output.size() >= OUTPUT_RING_LINES) {
                output.removeFirst();
            }
            output.add(line);
        }
    }

    private static void ensureTerminated(Process process, long deadline) {
        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        try {
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Captured server-launch configuration, read from system properties. */
    public record Config(
            Path serverJar,
            Path assets,
            Path pluginJar,
            Path workDir,
            String java,
            List<String> jvmArgs,
            long timeoutMillis,
            boolean showServerLogs) {

        static Config fromSystemProperties() {
            Path serverJar = requirePath("hytest.serverJar");
            Path pluginJar = requirePath("hytest.pluginJar");
            String assetsRaw = System.getProperty("hytest.assets");
            Path assets = (assetsRaw == null || assetsRaw.isBlank()) ? null : Path.of(assetsRaw);
            String workDirRaw = System.getProperty("hytest.serverDir");
            Path workDir;
            try {
                Path parent = (workDirRaw == null || workDirRaw.isBlank())
                        ? Path.of(System.getProperty("java.io.tmpdir"))
                        : Path.of(workDirRaw);
                workDir = Files.createTempDirectory(parent, "hytest-run-");
            } catch (IOException e) {
                throw new IllegalStateException("Could not create temp server dir", e);
            }
            String java = System.getProperty("hytest.java",
                    Path.of(System.getProperty("java.home"), "bin", "java").toString());
            List<String> jvmArgs = splitArgs(System.getProperty("hytest.jvmArgs", "-Xmx4G -Xms1G"));
            long timeoutSeconds = Long.parseLong(System.getProperty("hytest.timeoutSeconds", "300"));
            boolean showServerLogs = Boolean.getBoolean("hytest.showServerLogs");
            return new Config(serverJar, assets, pluginJar, workDir, java, jvmArgs,
                    timeoutSeconds * 1000L, showServerLogs);
        }

        private static Path requirePath(String key) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "Missing required system property " + key + " — the hytest Gradle plugin must set it.");
            }
            return Path.of(value);
        }

        private static List<String> splitArgs(String args) {
            List<String> out = new ArrayList<>();
            for (String token : args.split("\\s+")) {
                if (!token.isBlank()) {
                    out.add(token);
                }
            }
            return out;
        }
    }

    /** Lightweight holder so the dispatch logic stays free of descriptor-type imports. */
    public record MethodDescriptorRef(
            gg.ginco.hytest.junit5.descriptor.MethodDescriptor descriptor,
            ClassDescriptorRef classRef) {
    }

    /** @see ClassDescriptorRef */
    public record ClassDescriptorRef(
            gg.ginco.hytest.junit5.descriptor.ClassDescriptor descriptor) {
        public boolean beginIfFirst() {
            return descriptor.beginIfFirst();
        }

        public void methodFinished() {
            descriptor.methodFinished();
        }

        public boolean allMethodsFinished() {
            return descriptor.allMethodsFinished();
        }
    }
}
