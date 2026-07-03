package gg.ginco.hytest.junit5.discovery

data class DiscoveredTest(val className: String, val methodName: String) {
    fun key(): String = "$className#$methodName"
}
