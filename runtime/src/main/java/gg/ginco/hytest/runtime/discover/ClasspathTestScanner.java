package gg.ginco.hytest.runtime.discover;

import gg.ginco.hytest.runtime.api.HytaleTest;
import gg.ginco.hytest.runtime.runner.TestDescriptor;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans a classloader's own jar URLs for methods annotated with {@link HytaleTest}.
 *
 * <p>Only the supplied classloader's direct URLs are scanned (not parents), so
 * server classes on the parent classloader are never loaded by this scanner.
 * All classes in those jars are inspected, so tests may live in any package.
 */
public final class ClasspathTestScanner {

    private ClasspathTestScanner() {
    }

    public static List<TestDescriptor> discover(ClassLoader loader) {
        List<TestDescriptor> out = new ArrayList<>();
        if (!(loader instanceof URLClassLoader urlClassLoader)) {
            return out;
        }
        for (URL url : urlClassLoader.getURLs()) {
            if (!"file".equalsIgnoreCase(url.getProtocol())) {
                continue;
            }
            File file;
            try {
                file = new File(url.toURI());
            } catch (Exception e) {
                continue;
            }
            if (file.isFile() && file.getName().endsWith(".jar")) {
                scanJar(file, loader, out);
            }
        }
        return out;
    }

    private static void scanJar(File jarFile, ClassLoader loader, List<TestDescriptor> out) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                scanClass(loader, className, out);
            }
        } catch (Exception ignored) {
            // unreadable jar — skip
        }
    }

    private static void scanClass(ClassLoader loader, String className, List<TestDescriptor> out) {
        Class<?> type;
        try {
            type = loader.loadClass(className);
        } catch (Throwable ignored) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            HytaleTest annotation = method.getAnnotation(HytaleTest.class);
            if (annotation == null) {
                continue;
            }
            String display = annotation.value().isEmpty()
                    ? type.getSimpleName() + "." + method.getName()
                    : annotation.value();
            out.add(new TestDescriptor(type, method, display, annotation.timeoutSeconds()));
        }
    }
}
