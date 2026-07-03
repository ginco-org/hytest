package gg.ginco.hytest.junit5

import gg.ginco.hytest.junit5.descriptor.ClassDescriptor
import gg.ginco.hytest.junit5.descriptor.EngineRootDescriptor
import gg.ginco.hytest.junit5.descriptor.MethodDescriptor
import gg.ginco.hytest.junit5.discovery.HytestScanner
import gg.ginco.hytest.junit5.execution.ServerProcess
import java.util.LinkedHashMap
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId

class HytestEngine : TestEngine {

    override fun getId() = ID

    override fun discover(request: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
        val root = EngineRootDescriptor(uniqueId, "Hytale")
        val classes = linkedMapOf<String, ClassDescriptor>()
        for (found in HytestScanner.scan()) {
            val classDescriptor = classes.getOrPut(found.className) {
                ClassDescriptor(uniqueId, found.className).also { root.addChild(it) }
            }
            classDescriptor.addChild(
                MethodDescriptor(classDescriptor.uniqueId, found.className, found.methodName))
        }
        return root
    }

    override fun execute(request: ExecutionRequest) {
        val listener = request.engineExecutionListener
        val root = request.rootTestDescriptor as EngineRootDescriptor

        val classes = linkedMapOf<String, ClassDescriptor>()
        val methods = linkedMapOf<String, MethodDescriptor>()
        root.accept { descriptor ->
            when (descriptor) {
                is ClassDescriptor -> classes[descriptor.uniqueId.toString()] = descriptor
                is MethodDescriptor -> methods[descriptor.key()] = descriptor
            }
        }

        if (methods.isEmpty()) return

        val classRefs = classes.values.associateWith { ServerProcess.ClassDescriptorRef(it) }
        val methodRefs = linkedMapOf<String, ServerProcess.MethodDescriptorRef>()
        for (md in methods.values) {
            val cd = md.parent.get() as ClassDescriptor
            cd.registerMethod()
            methodRefs[md.key()] = ServerProcess.MethodDescriptorRef(md, classRefs[cd]!!)
        }

        listener.executionStarted(root)
        val showServerLogs = System.getProperty("hytest.showServerLogs", "false").toBoolean()
        try {
            ServerProcess.run(methodRefs, listener, showServerLogs)
            listener.executionFinished(root, TestExecutionResult.successful())
        } catch (e: RuntimeException) {
            listener.executionFinished(root, TestExecutionResult.failed(e))
        }
    }

    companion object {
        const val ID = "hytest"
    }
}
