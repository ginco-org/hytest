package gg.ginco.hytest.gradle;

import javax.inject.Inject;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration for the {@code hytest { }} extension. Defaults mirror the
 * sibling {@code hygradle} plugin when it is applied (same {@code run/} dir,
 * same server version); override anything explicitly here.
 */
public abstract class HytestExtension {

    /** Hytest artifact version to depend on. Defaults to {@code 0.1.0}. */
    public abstract Property<String> getVersion();

    /** Directory containing {@code HytaleServer.jar} and {@code Assets.zip}. */
    public abstract DirectoryProperty getServerDir();

    /** Explicit server jar path (overrides serverDir). */
    public abstract Property<String> getServerJar();

    /** Explicit assets path, a directory or zip (overrides serverDir). */
    public abstract Property<String> getAssets();

    /** Java executable used to launch the server. Defaults to the build JDK. */
    public abstract Property<String> getJavaExecutable();

    /** JVM arguments for the server process. */
    public abstract ListProperty<String> getJvmArgs();

    /** Server run timeout in seconds. */
    public abstract Property<Long> getTimeoutSeconds();

    /** Forward server stdout/stderr to the build console. */
    public abstract Property<Boolean> getShowServerLogs();

    @Inject
    public HytestExtension(Project project) {
        getVersion().convention("0.1.0");
        getServerDir().convention(project.getLayout().getProjectDirectory().dir("run"));
        getTimeoutSeconds().convention(300L);
        getShowServerLogs().convention(false);
        getJvmArgs().convention(java.util.List.of("-Xmx4G", "-Xms1G"));
    }
}
