package gg.ginco.hytest.junit5.descriptor

import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor

class EngineRootDescriptor(uniqueId: UniqueId, displayName: String) :
    AbstractTestDescriptor(uniqueId, displayName) {

    override fun getType() = org.junit.platform.engine.TestDescriptor.Type.CONTAINER
}
