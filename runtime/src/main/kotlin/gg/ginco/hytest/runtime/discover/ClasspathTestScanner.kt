package gg.ginco.hytest.runtime.discover

import gg.ginco.hytest.runtime.api.AfterEach
import gg.ginco.hytest.runtime.api.BeforeEach
import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.runner.TestDescriptor
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.jar.JarFile

object ClasspathTestScanner {

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

    private fun scanJar(jarFile: File, loader: ClassLoader, out: MutableList<TestDescriptor>) {
        try { JarFile(jarFile).use { jar ->
            for (entry in jar.entries()) {
                val name = entry.name
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue
                scanClass(loader, name.removeSuffix(".class").replace('/', '.'), out)
            }
        }} catch (_: Exception) { /* unreadable jar */ }
    }

    private fun scanClass(loader: ClassLoader, className: String, out: MutableList<TestDescriptor>) {
        val type = try { loader.loadClass(className) } catch (_: Throwable) { return }
        val beforeEach = mutableListOf<Method>()
        val afterEach = mutableListOf<Method>()
        for (m in type.declaredMethods) {
            if (m.isAnnotationPresent(BeforeEach::class.java)) beforeEach.add(m)
            if (m.isAnnotationPresent(AfterEach::class.java)) afterEach.add(m)
        }
        for (m in type.declaredMethods) {
            val a = m.getAnnotation(HytaleTest::class.java) ?: continue
            val display = a.value.ifEmpty { "${type.simpleName}.${m.name}" }
            out.add(TestDescriptor(type, m, display, a.timeoutSeconds, beforeEach, afterEach))
        }
    }
}
