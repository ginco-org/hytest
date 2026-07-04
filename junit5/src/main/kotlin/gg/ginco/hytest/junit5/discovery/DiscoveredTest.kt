package gg.ginco.hytest.junit5.discovery

data class DiscoveredTest(
    val className: String,
    val methodName: String,
    val displayName: String = "",
    val timeoutSeconds: Long = 30
) {
    fun key(): String = "$className#$methodName"

    val effectiveDisplayName: String get() = displayName.ifEmpty { "$className.$methodName" }
}
