package gg.ginco.hytest.runtime.report

object Json {
    fun stringify(value: Any?): String = buildString { write(value) }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> writeString(value)
            is Boolean, is Number -> append(value)
            is Map<*, *> -> writeMap(value)
            is Iterable<*> -> writeArray(value)
            else -> writeString(value.toString())
        }
    }

    private fun StringBuilder.writeMap(map: Map<*, *>) {
        append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) append(',')
            first = false
            writeString(k.toString())
            append(':')
            write(v)
        }
        append('}')
    }

    private fun StringBuilder.writeArray(items: Iterable<*>) {
        append('[')
        var first = true
        for (item in items) {
            if (!first) append(',')
            first = false
            write(item)
        }
        append(']')
    }

    private fun StringBuilder.writeString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code))
                else append(c)
            }
        }
        append('"')
    }
}
