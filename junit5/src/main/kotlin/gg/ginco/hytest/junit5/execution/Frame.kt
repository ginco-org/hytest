package gg.ginco.hytest.junit5.execution

import gg.ginco.hytest.junit5.json.parseJson

data class Frame(
    val type: String,
    val className: String?,
    val method: String?,
    val status: String?,
    val durationMs: Long,
    val error: String?,
    val message: String?
) {
    fun testKey(): String = "$className#$method"

    companion object {
        const val PREFIX = "HYTEST "

        fun isFrame(line: String): Boolean = line.startsWith(PREFIX)

        @Suppress("UNCHECKED_CAST")
        fun parse(line: String): Frame {
            val json = line.removePrefix(PREFIX)
            val map = parseJson(json) as Map<String, Any?>
            return Frame(
                type = map["type"]?.toString().orEmpty(),
                className = map["class"]?.toString(),
                method = map["method"]?.toString(),
                status = map["status"]?.toString(),
                durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
                error = map["error"]?.toString(),
                message = map["message"]?.toString()
            )
        }
    }
}
