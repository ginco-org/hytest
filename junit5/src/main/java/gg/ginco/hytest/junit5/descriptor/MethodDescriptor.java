package gg.ginco.hytest.junit5.descriptor;

import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;

/** A single {@code @HytaleTest} method — a JUnit leaf test. */
public final class MethodDescriptor extends AbstractTestDescriptor {

    private final String className;
    private final String methodName;

    public MethodDescriptor(UniqueId parent, String className, String methodName) {
        super(parent.append("method", methodName),
                className + "." + methodName,
                MethodSource.from(className, methodName));
        this.className = className;
        this.methodName = methodName;
    }

    @Override
    public org.junit.platform.engine.TestDescriptor.Type getType() {
        return org.junit.platform.engine.TestDescriptor.Type.TEST;
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String key() {
        return className + "#" + methodName;
    }
}
