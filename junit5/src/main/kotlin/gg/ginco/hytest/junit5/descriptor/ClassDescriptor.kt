package gg.ginco.hytest.junit5.descriptor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource

class ClassDescriptor(parent: UniqueId, className: String) :
    AbstractTestDescriptor(parent.append("class", className), className, ClassSource.from(className)) {

    private val started = AtomicBoolean(false)
    private val remainingMethods = AtomicInteger(0)

    override fun getType() = org.junit.platform.engine.TestDescriptor.Type.CONTAINER

    fun registerMethod() { remainingMethods.incrementAndGet() }

    fun beginIfFirst(): Boolean = started.compareAndSet(false, true)

    fun methodFinished() { remainingMethods.decrementAndGet() }

    fun allMethodsFinished(): Boolean = remainingMethods.get() <= 0
}
