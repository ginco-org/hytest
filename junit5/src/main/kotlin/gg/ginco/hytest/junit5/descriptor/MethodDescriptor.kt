package gg.ginco.hytest.junit5.descriptor

import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.MethodSource

class MethodDescriptor(parent: UniqueId, val className: String, val methodName: String) :
    AbstractTestDescriptor(
        parent.append("method", methodName),
        "$className.$methodName",
        MethodSource.from(className, methodName)
    ) {

    override fun getType() = org.junit.platform.engine.TestDescriptor.Type.TEST

    fun key(): String = "$className#$methodName"
}
