package gg.ginco.hytest.junit5;

import gg.ginco.hytest.junit5.descriptor.ClassDescriptor;
import gg.ginco.hytest.junit5.descriptor.EngineRootDescriptor;
import gg.ginco.hytest.junit5.descriptor.MethodDescriptor;
import gg.ginco.hytest.junit5.discovery.DiscoveredTest;
import gg.ginco.hytest.junit5.discovery.HytestScanner;
import gg.ginco.hytest.junit5.execution.ServerProcess;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;

/**
 * JUnit 5 test engine that runs {@code @HytaleTest} methods inside a real Hytale
 * server. Discovery scans the build's test classpath for the annotation (via ASM,
 * without loading classes); execution launches the server subprocess, deploys the
 * merged Hytest plugin, and relays result frames to JUnit.
 *
 * <p>Required system properties (set by the {@code hytest-gradle} plugin):
 * {@code hytest.serverJar}, {@code hytest.pluginJar}. Optional:
 * {@code hytest.assets}, {@code hytest.serverDir}, {@code hytest.jvmArgs},
 * {@code hytest.timeoutSeconds}, {@code hytest.showServerLogs}.
 */
public final class HytestEngine implements TestEngine {

    public static final String ID = "hytest";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineRootDescriptor root = new EngineRootDescriptor(uniqueId, "Hytale");
        Map<String, ClassDescriptor> classes = new LinkedHashMap<>();
        for (DiscoveredTest found : HytestScanner.scan()) {
            ClassDescriptor classDescriptor = classes.get(found.className());
            if (classDescriptor == null) {
                classDescriptor = new ClassDescriptor(uniqueId, found.className());
                classes.put(found.className(), classDescriptor);
                root.addChild(classDescriptor);
            }
            classDescriptor.addChild(
                    new MethodDescriptor(classDescriptor.getUniqueId(), found.className(), found.methodName()));
        }
        return root;
    }

    @Override
    public void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        EngineRootDescriptor root = (EngineRootDescriptor) request.getRootTestDescriptor();

        Map<String, ClassDescriptor> classes = new LinkedHashMap<>();
        Map<String, MethodDescriptor> methods = new LinkedHashMap<>();
        root.accept(descriptor -> {
            if (descriptor instanceof ClassDescriptor cd) {
                classes.put(cd.getUniqueId().toString(), cd);
            } else if (descriptor instanceof MethodDescriptor md) {
                methods.put(md.key(), md);
            }
        });

        if (methods.isEmpty()) {
            return;
        }

        Map<ClassDescriptor, ServerProcess.ClassDescriptorRef> classRefs = new LinkedHashMap<>();
        for (ClassDescriptor cd : classes.values()) {
            classRefs.put(cd, new ServerProcess.ClassDescriptorRef(cd));
        }
        Map<String, ServerProcess.MethodDescriptorRef> methodRefs = new LinkedHashMap<>();
        for (MethodDescriptor md : methods.values()) {
            ClassDescriptor cd = (ClassDescriptor) md.getParent().orElseThrow();
            cd.registerMethod();
            methodRefs.put(md.key(), new ServerProcess.MethodDescriptorRef(md, classRefs.get(cd)));
        }

        listener.executionStarted(root);
        boolean showServerLogs = Boolean.getBoolean("hytest.showServerLogs");
        ServerProcess.run(methodRefs, listener, showServerLogs);
        listener.executionFinished(root, TestExecutionResult.successful());
    }
}
