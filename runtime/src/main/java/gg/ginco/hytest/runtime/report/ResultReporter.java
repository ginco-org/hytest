package gg.ginco.hytest.runtime.report;

import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits one JSON object per line to the server's raw stdout (bypassing the ANSI
 * log formatter) so an external harness can parse results. Each line is prefixed
 * with {@link #PREFIX} to distinguish it from ordinary server output.
 */
public final class ResultReporter {

    /** Line prefix used by all hytest frames. */
    public static final String PREFIX = "HYTEST ";

    private ResultReporter() {
    }

    public static void runStarted(int count) {
        emit(Map.of("type", "run_started", "count", count));
    }

    public static void testStarted(String className, String method) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "test_started");
        frame.put("class", className);
        frame.put("method", method);
        emit(frame);
    }

    public static void testFinished(String className, String method, boolean passed,
                                    long durationMs, Throwable error) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "test_finished");
        frame.put("class", className);
        frame.put("method", method);
        frame.put("status", passed ? "passed" : "failed");
        frame.put("durationMs", durationMs);
        if (!passed && error != null) {
            frame.put("error", toString(error));
        }
        emit(frame);
    }

    public static void runFinished(int total, int passed, int failed, int exitCode) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "run_finished");
        frame.put("total", total);
        frame.put("passed", passed);
        frame.put("failed", failed);
        frame.put("exitCode", exitCode);
        emit(frame);
    }

    /** Emits a non-fatal diagnostic (discovery issues, config hints). */
    public static void warning(String message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "warning");
        frame.put("message", message);
        emit(frame);
    }

    private static void emit(Map<String, Object> frame) {
        HytaleLoggerBackend.REAL_SOUT.println(PREFIX + Json.stringify(frame));
    }

    private static String toString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append("\n\tat ").append(element);
        }
        return sb.toString();
    }
}
