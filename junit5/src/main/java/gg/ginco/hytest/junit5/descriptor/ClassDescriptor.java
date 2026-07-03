package gg.ginco.hytest.junit5.descriptor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;

/** A container grouping the {@code @HytaleTest} methods of a single class. */
public final class ClassDescriptor extends AbstractTestDescriptor {

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger remainingMethods = new AtomicInteger(0);

    public ClassDescriptor(UniqueId parent, String className) {
        super(parent.append("class", className), className, ClassSource.from(className));
    }

    @Override
    public org.junit.platform.engine.TestDescriptor.Type getType() {
        return org.junit.platform.engine.TestDescriptor.Type.CONTAINER;
    }

    public void registerMethod() {
        remainingMethods.incrementAndGet();
    }

    public boolean beginIfFirst() {
        return started.compareAndSet(false, true);
    }

    public void methodFinished() {
        remainingMethods.decrementAndGet();
    }

    public boolean allMethodsFinished() {
        return remainingMethods.get() <= 0;
    }
}
