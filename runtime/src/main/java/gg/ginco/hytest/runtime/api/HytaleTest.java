package gg.ginco.hytest.runtime.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an instance method as a Hytale test. The method must take a single
 * {@link HytaleTestHelper} parameter and have a declaring class with a public
 * no-argument constructor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HytaleTest {
    /** Optional display name; defaults to {@code Class.method}. */
    String value() default "";

    /** Wall-clock budget before the test is failed as a timeout. */
    long timeoutSeconds() default 30;
}
