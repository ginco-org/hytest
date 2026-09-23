package gg.ginco.hytest.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the `manifest.json` for the merged Hytest plugin jar.
 *
 * ## Why the plugin under test owns the manifest
 *
 * The server reads exactly one `manifest.json` per jar and instantiates its
 * `Main`. With hytest's own manifest the server only ever starts
 * `HytestPlugin`, so the plugin under test never runs and every project had to
 * re-mount its own plugin by hand inside the tests.
 *
 * Instead, in hygradle builds the merged jar carries the **plugin under test's
 * manifest** with hytest attached as a `SubPlugins` entry. The server then
 * loads both from the same jar and classloader (`PendingLoadJavaPlugin
 * .createSubPendingLoadPlugin` reuses the parent's classloader), so:
 *
 * - the plugin under test gets its real identity, data directory and full
 *   `setup()/start()` lifecycle — nothing is stubbed,
 * - hytest-runtime starts as a real plugin too,
 * - test classes share one class identity with plugin classes (component
 *   companions like `Phase.componentType` are the *same* statics the plugin
 *   registered), which two separate jars could never provide.
 *
 * Sub-plugins inherit missing manifest fields from the parent (e.g.
 * `ServerVersion`), so the entry only needs identity + Main.
 *
 * Without hygradle there is no plugin manifest to attach to, so the task is
 * not registered and the runtime's own manifest.json ships unchanged
 * (legacy single-plugin behaviour).
 */
abstract class HytestManifestTask : DefaultTask() {

    @get:Input abstract val pluginGroup: Property<String>
    @get:Input abstract val pluginName: Property<String>
    @get:Input @get:Optional abstract val pluginDescription: Property<String>
    @get:Input abstract val pluginMainClass: Property<String>
    @get:Input @get:Optional abstract val pluginServerVersion: Property<String>
    @get:Input abstract val pluginVersion: Property<String>

    @get:Input abstract val hytestVersion: Property<String>

    @get:OutputFile abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val json = buildString {
            append("{\n")
            append("  \"Group\": ").append(quote(pluginGroup.get())).append(",\n")
            append("  \"Name\": ").append(quote(pluginName.get())).append(",\n")
            append("  \"Version\": ").append(quote(pluginVersion.get())).append(",\n")
            val description = pluginDescription.orNull
            if (!description.isNullOrBlank()) {
                append("  \"Description\": ").append(quote(description)).append(",\n")
            }
            append("  \"Main\": ").append(quote(pluginMainClass.get())).append(",\n")
            val serverVersion = pluginServerVersion.orNull
            if (!serverVersion.isNullOrBlank()) {
                append("  \"ServerVersion\": ").append(quote(toSemverRange(serverVersion))).append(",\n")
            }
            append("  \"SubPlugins\": [\n")
            append("    {\n")
            append("      \"Group\": \"Ginco\",\n")
            append("      \"Name\": \"Hytest\",\n")
            append("      \"Version\": ").append(quote(hytestVersion.get())).append(",\n")
            append("      \"Main\": \"gg.ginco.hytest.runtime.HytestPlugin\"\n")
            append("    }\n")
            append("  ]\n")
            append("}\n")
        }
        manifestFile.get().asFile.writeText(json)
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * hygradle normalizes the same input before writing its own manifests
     * (`ManifestJsonKt.toSemverRange`); the merged manifest must match or the
     * server rejects it — 0.6.x `SemverRange` refuses bare versions whose
     * patch is non-zero ("0.6.8") while accepting exact ("=0.6.8") and range
     * ("^0.6.8", "0.6.x", …) forms.
     */
    private fun toSemverRange(version: String): String {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.first().isDigit()) "=$trimmed" else trimmed
    }
}
