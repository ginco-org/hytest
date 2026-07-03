package gg.ginco.hytest.junit5.execution;

import gg.ginco.hytest.junit5.json.Json;
import java.util.Map;

/**
 * A parsed {@code HYTEST ...} result frame emitted by the runtime on stdout.
 *
 * @param type       frame type: {@code test_started}, {@code test_finished},
 *                   {@code run_started}, {@code run_finished}, {@code warning}
 * @param className   fully-qualified test class (present for test_* frames)
 * @param method     test method name (present for test_* frames)
 * @param status     {@code passed} or {@code failed} (test_finished only)
 * @param durationMs wall-clock duration (test_finished only)
 * @param error      failure detail string with stack trace (test_finished, failed only)
 * @param message    human-readable message ({@code warning} frames)
 */
public record Frame(
        String type,
        String className,
        String method,
        String status,
        long durationMs,
        String error,
        String message) {

    /** Prefix the runtime writes before every frame line. */
    public static final String PREFIX = "HYTEST ";

    public String testKey() {
        return className + "#" + method;
    }

    public static boolean isFrame(String line) {
        return line.startsWith(PREFIX);
    }

    @SuppressWarnings("unchecked")
    public static Frame parse(String line) {
        String json = line.substring(PREFIX.length());
        Object parsed = Json.parse(json);
        Map<String, Object> map = (Map<String, Object>) parsed;
        return new Frame(
                string(map, "type"),
                string(map, "class"),
                string(map, "method"),
                string(map, "status"),
                longValue(map, "durationMs"),
                string(map, "error"),
                string(map, "message"));
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
