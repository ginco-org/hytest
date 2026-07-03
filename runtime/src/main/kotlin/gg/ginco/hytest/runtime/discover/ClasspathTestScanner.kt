package gg.ginco.hytest.runtime.discover

import gg.ginco.hytest.runtime.api.HytaleTest
import gg.ginco.hytest.runtime.runner.TestDescriptor
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

object ClasspathTestScanner {

    fun discover(loader: ClassLoader): List<TestDescriptor> {
        val out = mutableListOf<TestDescriptor>()
        if (loader !is URLClassLoader) return out
        for (url in loader.urLs) {
            if (!"file".equals(url.protocol, ignoreCase = true)) continue
            val file = try { File(url.toURI()) } catch (_: Exception) { continue }
            if (file.isFile && file.name.endsWith(".jar")) scanJar(file, loader, out)
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
        for (m in type.declaredMethods) {
            val a = m.getAnnotation(HytaleTest::class.java) ?: continue
            val display = a.value.ifEmpty { "${type.simpleName}.${m.name}" }
            out.add(TestDescriptor(type, m, display, a.timeoutSeconds))
        }
    }
}
