package gg.ginco.hytest.junit5.discovery;

/**
 * A test discovered on the build classpath — the fully-qualified class and the
 * method name. Only the names are needed at discovery time; annotation values
 * like {@code timeoutSeconds} are read by the server-side runtime at execution.
 */
public record DiscoveredTest(String className, String methodName) {
    public String key() {
        return className + "#" + methodName;
    }
}
