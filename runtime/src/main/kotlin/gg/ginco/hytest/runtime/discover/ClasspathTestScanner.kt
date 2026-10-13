package gg.ginco.hytest.runtime.discover

import gg.ginco.hytest.runtime.api.AfterEach
import gg.ginco.hytest.runtime.api.BeforeEach
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.runner.TestDescriptor
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

object ClasspathTestScanner {

    private const val ANNOTATION_DESCRIPTOR = "Lgg/ginco/hytest/runtime/api/HytaleTest;"

    /** Annotation metadata only — no bodies, debug info or frames needed. */
    private const val PARSING_FLAGS =
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES

    fun discover(loader: ClassLoader): List<TestDescriptor> {
        val out = mutableListOf<TestDescriptor>()
        val roots = mutableListOf<File>()
        if (loader is URLClassLoader) {
            for (url in loader.urLs) {
                if (!"file".equals(url.protocol, ignoreCase = true)) continue
                try { roots.add(File(url.toURI())) } catch (_: Exception) {}
            }
        }
        val classPath = System.getProperty("java.class.path") ?: ""
        for (entry in classPath.split(File.pathSeparator).filter { it.isNotBlank() }) {
            val f = File(entry)
            if (f.exists()) roots.add(f)
        }
        for (root in roots.distinctBy { it.absolutePath }) {
            if (root.isFile && root.name.endsWith(".jar")) scanJar(root, loader, out)
        }
        return out
    }

    /**
     * Two-pass scan: ASM reads raw bytecode (no class loading) to pick classes
     * carrying @HytaleTest, then reflection resolves only those. The server
     * classpath includes the ~100MB server jar; the old load-everything pass
     * resolved every method signature of every loadable class and dominated
     * boot. Classes without the annotation are never loaded.
     */
    private fun scanJar(jarFile: File, loader: ClassLoader, out: MutableList<TestDescriptor>) {
        try { JarFile(jarFile).use { jar ->
            val annotated = mutableSetOf<String>()
            for (entry in jar.entries()) {
                val name = entry.name
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue
                val bytes = try { jar.getInputStream(entry).readAllBytes() } catch (_: Exception) { continue }
                if (hasHytaleTestAnnotation(bytes))
                    annotated.add(name.removeSuffix(".class").replace('/', '.'))
            }
            for (className in annotated) scanClass(loader, className, out)
        }} catch (_: Exception) { /* unreadable jar */ }
    }

    /** True when any method of this class carries @HytaleTest — bytecode only, no class loading. */
    private fun hasHytaleTestAnnotation(classBytes: ByteArray): Boolean {
        var found = false
        try {
            ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        descriptor: String, visible: Boolean
                    ): AnnotationVisitor? {
                        if (visible && ANNOTATION_DESCRIPTOR == descriptor) found = true
                        return null
                    }
                }
            }, PARSING_FLAGS)
        } catch (_: Exception) { /* malformed class — skip */ }
        return found
    }

    private fun scanClass(loader: ClassLoader, className: String, out: MutableList<TestDescriptor>) {
        val type = try { loader.loadClass(className) } catch (_: Throwable) { return }
        // getDeclaredMethods resolves every type in every method signature. Classes on the
        // server classpath may reference types this server version no longer ships (e.g.
        // protobuf-nano classes removed in 0.6.2) — skip those instead of killing discovery.
        val declared = try { type.declaredMethods.toList() } catch (_: Throwable) { return }
        val beforeEach = mutableListOf<Method>()
        val afterEach = mutableListOf<Method>()
        for (m in declared) {
            if (m.isAnnotationPresent(BeforeEach::class.java)) beforeEach.add(m)
            if (m.isAnnotationPresent(AfterEach::class.java)) afterEach.add(m)
        }
        for (m in declared) {
            val a = m.getAnnotation(HytaleTest::class.java) ?: continue
            val display = a.value.ifEmpty { "${type.simpleName}.${m.name}" }
            out.add(TestDescriptor(type, m, display, a.timeoutSeconds, beforeEach, afterEach))
        }
    }
}
