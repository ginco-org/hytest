package gg.ginco.hytest.runtime.runner;

import java.lang.reflect.Method;

/**
 * A discovered {@link gg.ginco.hytest.runtime.api.HytaleTest} method and its metadata.
 */
public record TestDescriptor(Class<?> testClass, Method method, String displayName, long timeoutSeconds) {
}
