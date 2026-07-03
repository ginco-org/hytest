package gg.ginco.hytest.junit5.discovery

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.jar.JarFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

object HytestScanner {

    private const val ANNOTATION_DESCRIPTOR = "Lgg/ginco/hytest/runtime/api/HytaleTest;"
    private const val PARSING_FLAGS = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES

    fun scan(): List<DiscoveredTest> {
        val out = mutableListOf<DiscoveredTest>()
        for (root in classpathRoots()) {
            scanRoot(root, out)
        }
        return out
    }

    private fun classpathRoots(): Set<Path> {
        val roots = LinkedHashSet<Path>()
        var loader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                for (url in loader.urLs) {
                    if ("file".equals(url.protocol, ignoreCase = true)) {
                        try { roots.add(Path.of(url.toURI())) } catch (_: Exception) {}
                    }
                }
            }
            loader = loader.parent
        }
        val classPath = System.getProperty("java.class.path") ?: ""
        for (entry in classPath.split(File.pathSeparator).filter { it.isNotBlank() }) {
            roots.add(Path.of(entry))
        }
        return roots
    }

    private fun scanRoot(root: Path, out: MutableList<DiscoveredTest>) {
        val file = root.toFile()
        if (!file.exists()) return
        if (file.isDirectory) scanDirectory(file, file, out)
        else if (file.name.endsWith(".jar")) scanJar(file, out)
    }

    private fun scanDirectory(root: File, dir: File, out: MutableList<DiscoveredTest>) {
        for (child in dir.listFiles() ?: emptyArray()) {
            if (child.isDirectory) scanDirectory(root, child, out)
            else if (child.name.endsWith(".class")) {
                try { scanClass(Files.readAllBytes(child.toPath()), out) } catch (_: Exception) {}
            }
        }
    }

    private fun scanJar(jarFile: File, out: MutableList<DiscoveredTest>) {
        try { JarFile(jarFile).use { jar ->
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class")) continue
                try {
                    val bytes = jar.getInputStream(entry).readAllBytes()
                    scanClass(bytes, out)
                } catch (_: Exception) {}
            }
        }} catch (_: Exception) {}
    }

    private fun scanClass(classBytes: ByteArray, out: MutableList<DiscoveredTest>) {
        try {
            val reader = ClassReader(classBytes)
            // run the visitor but do not load the class
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                private var className = ""

                override fun visit(
                    version: Int, access: Int, name: String, signature: String?,
                    superName: String?, interfaces: Array<out String>?
                ) {
                    this.className = name
                }

                override fun visitMethod(
                    access: Int, name: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?
                ): MethodVisitor {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String, visible: Boolean
                        ): AnnotationVisitor? {
                            if (visible && ANNOTATION_DESCRIPTOR == descriptor) {
                                out.add(DiscoveredTest(className.replace('/', '.'), name))
                            }
                            return null
                        }
                    }
                }
            }, PARSING_FLAGS)
        } catch (_: RuntimeException) { /* malformed class file — skip */ }
    }
}
