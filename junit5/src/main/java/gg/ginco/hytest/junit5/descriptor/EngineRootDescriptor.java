package gg.ginco.hytest.junit5.descriptor;

import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

/** The engine root — a container for class-level descriptors. */
public final class EngineRootDescriptor extends AbstractTestDescriptor {

    public EngineRootDescriptor(UniqueId uniqueId, String displayName) {
        super(uniqueId, displayName);
    }

    @Override
    public org.junit.platform.engine.TestDescriptor.Type getType() {
        return org.junit.platform.engine.TestDescriptor.Type.CONTAINER;
    }
}
