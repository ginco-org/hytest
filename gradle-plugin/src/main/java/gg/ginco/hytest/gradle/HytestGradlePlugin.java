package gg.ginco.hytest.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;

/**
 * Gradle plugin ({@code id = "gg.ginco.hytest"}) that wires a {@code hytaleTest}
 * source set whose {@code @HytaleTest} methods run inside a real Hytale server
 * via the {@code hytest-junit5} engine.
 *
 * <p>Cooperates with {@code gg.ginco.hygradle} when applied: reuses its server
 * directory and the {@code hytaleApi} compile configuration. The {@code hytaleTest}
 * task launches the server (files expected in the server dir) and reports results
 * through JUnit.
 */
public final class HytestGradlePlugin implements Plugin<Project> {

    public static final String SOURCE_SET = "hytaleTest";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        HytestExtension ext = project.getExtensions().create("hytest", HytestExtension.class, project);
        applyHygradleDefaults(project, ext);

        SourceSetContainer sourceSets = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets();
        SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet hytaleTest = sourceSets.create(SOURCE_SET);

        wireSourceSetToMain(project, hytaleTest, main);
        wireServerApi(project, hytaleTest);

        String hytestVersion = ext.getVersion().get();
        Configuration runtimePlugin = project.getConfigurations().create("hytestRuntimePlugin", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setDescription("hytest-runtime jar merged into the deployed plugin");
        });

        String junitPlatform = resolveJunitPlatformVersion(project);
        project.getDependencies().add(runtimePlugin.getName(), "gg.ginco.hytest:runtime:" + hytestVersion);
        project.getDependencies().add(hytaleTest.getImplementationConfigurationName(),
                "gg.ginco.hytest:runtime:" + hytestVersion);
        project.getDependencies().add(hytaleTest.getRuntimeOnlyConfigurationName(),
                "gg.ginco.hytest:junit5:" + hytestVersion);
        project.getDependencies().add(hytaleTest.getRuntimeOnlyConfigurationName(),
                "org.junit.platform:junit-platform-launcher:" + junitPlatform);

        TaskProvider<Jar> mergedJar = registerMergedJarTask(project, runtimePlugin, hytaleTest);
        registerTestTask(project, ext, sourceSets, mergedJar);
    }

    private void wireSourceSetToMain(Project project, SourceSet hytaleTest, SourceSet main) {
        project.getConfigurations().named(hytaleTest.getImplementationConfigurationName(), c ->
                c.extendsFrom(project.getConfigurations().getByName("implementation")));
        project.getConfigurations().named(hytaleTest.getCompileOnlyConfigurationName(), c ->
                c.extendsFrom(project.getConfigurations().getByName("compileOnly")));
        project.getConfigurations().named(hytaleTest.getRuntimeOnlyConfigurationName(), c ->
                c.extendsFrom(project.getConfigurations().getByName("runtimeOnly")));
        project.getDependencies().add(hytaleTest.getImplementationConfigurationName(), main.getOutput());
    }

    private void wireServerApi(Project project, SourceSet hytaleTest) {
        // hygradle exposes the server jar as the compile-only 'hytaleApi' configuration.
        Configuration hytaleApi = project.getConfigurations().findByName("hytaleApi");
        if (hytaleApi != null) {
            project.getConfigurations()
                    .getByName(hytaleTest.getCompileOnlyConfigurationName())
                    .extendsFrom(hytaleApi);
        }
    }

    private TaskProvider<Jar> registerMergedJarTask(
            Project project, Configuration runtimePlugin, SourceSet hytaleTest) {
        return project.getTasks().register("createHytaleTestPlugin", Jar.class, task -> {
            task.setDescription("Builds the merged Hytest plugin jar (runtime + hytaleTest classes) for server deployment.");
            task.getArchiveBaseName().set("Hytest");
            task.getArchiveClassifier().set("");
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.from(project.provider(() -> {
                List<Object> trees = new ArrayList<>();
                for (File file : runtimePlugin) {
                    if (file.getName().endsWith(".jar")) {
                        trees.add(project.zipTree(file));
                    }
                }
                return trees;
            }));
            task.from(hytaleTest.getOutput());
        });
    }

    private void registerTestTask(
            Project project, HytestExtension ext, SourceSetContainer sourceSets,
            TaskProvider<Jar> mergedJar) {
        SourceSet hytaleTest = sourceSets.getByName(SOURCE_SET);
        var testTask = project.getTasks().register("hytaleTest", Test.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs @HytaleTest methods inside a Hytale server.");
            task.setTestClassesDirs(hytaleTest.getOutput().getClassesDirs());
            task.setClasspath(hytaleTest.getRuntimeClasspath());
            task.useJUnitPlatform();
            task.dependsOn(mergedJar);
            // Isolate each server run in build/hytale-server so it never clashes with hygradle's run/.
            task.systemProperty("hytest.serverDir", project.getLayout()
                    .getBuildDirectory().dir("hytale-server").get().getAsFile().getAbsolutePath());
            task.systemProperty("hytest.jvmArgs", String.join(" ", ext.getJvmArgs().get()));
            task.systemProperty("hytest.timeoutSeconds", String.valueOf(ext.getTimeoutSeconds().get()));
            task.systemProperty("hytest.showServerLogs", String.valueOf(ext.getShowServerLogs().get()));
            if (ext.getJavaExecutable().isPresent()) {
                task.systemProperty("hytest.java", ext.getJavaExecutable().get());
            }
            // Resolved at task configuration time (paths are stable).
            File serverJar = resolveServerJar(project, ext);
            File assets = resolveAssets(project, ext);
            if (serverJar == null || !serverJar.isFile()) {
                task.doFirst(t -> {
                    throw new IllegalStateException(
                            "HytaleServer.jar not found. Run './gradlew runServer' once to provision the server, "
                                    + "or configure hytest { serverJar } / serverDir. Searched: "
                                    + (serverJar == null ? "<serverDir>" : serverJar.getAbsolutePath()));
                });
            } else {
                task.systemProperty("hytest.serverJar", serverJar.getAbsolutePath());
                if (assets != null && assets.isFile()) {
                    task.systemProperty("hytest.assets", assets.getAbsolutePath());
                }
            }
            task.systemProperty("hytest.pluginJar", mergedJar.get().getArchiveFile().get().getAsFile().getAbsolutePath());
        });
    }

    private File resolveServerJar(Project project, HytestExtension ext) {
        if (ext.getServerJar().isPresent()) {
            return project.file(ext.getServerJar().get());
        }
        return ext.getServerDir().file("HytaleServer.jar").get().getAsFile();
    }

    private File resolveAssets(Project project, HytestExtension ext) {
        if (ext.getAssets().isPresent()) {
            return project.file(ext.getAssets().get());
        }
        return ext.getServerDir().file("Assets.zip").get().getAsFile();
    }

    private void applyHygradleDefaults(Project project, HytestExtension ext) {
        Object hytale = project.getExtensions().findByName("hytale");
        if (hytale == null) {
            return;
        }
        try {
            Object server = hytale.getClass().getMethod("getServer").invoke(hytale);
            Object serverDir = server.getClass().getMethod("getServerDir").invoke(server);
            if (serverDir instanceof DirectoryProperty directoryProperty) {
                ext.getServerDir().convention(directoryProperty);
            }
        } catch (Exception ignored) {
            // hygradle present but shape differs — leave defaults
        }
    }

    private String resolveJunitPlatformVersion(Project project) {
        Object property = project.findProperty("junitPlatformVersion");
        return property != null ? property.toString() : "6.1.1";
    }
}
