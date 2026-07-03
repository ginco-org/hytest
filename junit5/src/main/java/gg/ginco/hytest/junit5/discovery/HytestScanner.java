package gg.ginco.hytest.junit5.discovery;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Scans the runtime classpath (the build's test classpath) for methods annotated
 * with {@code @HytaleTest} by reading class bytecode with ASM — without loading
 * the classes. This keeps server classes off the test JVM entirely.
 */
public final class HytestScanner {

    /** Descriptor of the runtime-visible {@code @HytaleTest} annotation. */
    private static final String ANNOTATION_DESCRIPTOR = "Lgg/ginco/hytest/runtime/api/HytaleTest;";

    private static final int PARSING_FLAGS =
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

    private HytestScanner() {
    }

    public static List<DiscoveredTest> scan() {
        List<DiscoveredTest> out = new ArrayList<>();
        Set<Path> roots = classpathRoots();
        for (Path root : roots) {
            scanRoot(root, out);
        }
        return out;
    }

    private static Set<Path> classpathRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        try {
                            roots.add(Path.of(url.toURI()));
                        } catch (Exception ignored) {
                            // skip malformed entries
                        }
                    }
                }
            }
            loader = loader.getParent();
        }
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            for (String entry : classPath.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    roots.add(Path.of(entry));
                }
            }
        }
        return roots;
    }

    private static void scanRoot(Path root, List<DiscoveredTest> out) {
        File file = root.toFile();
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            scanDirectory(file, file, out);
        } else if (file.getName().endsWith(".jar")) {
            scanJar(file, out);
        }
    }

    private static void scanDirectory(File root, File dir, List<DiscoveredTest> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanDirectory(root, child, out);
            } else if (child.getName().endsWith(".class")) {
                try {
                    byte[] bytes = Files.readAllBytes(child.toPath());
                    scanClass(bytes, out);
                } catch (IOException ignored) {
                    // unreadable class file
                }
            }
        }
    }

    private static void scanJar(File jarFile, List<DiscoveredTest> out) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    scanClass(bytes, out);
                } catch (IOException ignored) {
                    // skip unreadable entry
                }
            }
        } catch (IOException ignored) {
            // not a readable jar
        }
    }

    private static void scanClass(byte[] classBytes, List<DiscoveredTest> out) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                private String className;

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    this.className = name;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (visible && ANNOTATION_DESCRIPTOR.equals(descriptor)) {
                                out.add(new DiscoveredTest(className.replace('/', '.'), name));
                            }
                            return null;
                        }
                    };
                }
            }, PARSING_FLAGS);
        } catch (RuntimeException ignored) {
            // not valid class file — skip
        }
    }
}
