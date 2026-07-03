package gg.ginco.hytest.junit5.json

class JsonParser(private val src: String) {
    private var pos = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) throw error("unexpected end of input")
        return when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        expect('{')
        skipWhitespace()
        if (peek() == '}') { next(); return map }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            map[key] = parseValue()
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> break
                else -> throw error("expected ',' or '}'")
            }
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = mutableListOf<Any?>()
        expect('[')
        skipWhitespace()
        if (peek() == ']') { next(); return list }
        while (true) {
            list.add(parseValue())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> break
                else -> throw error("expected ',' or ']'")
            }
        }
        return list
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < src.length) {
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw error("unterminated escape")
                    sb.append(
                        when (val esc = src[pos++]) {
                            '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                            'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'
                            'r' -> '\r'; 't' -> '\t'
                            'u' -> {
                                if (pos + 4 > src.length) throw error("unterminated unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                hex.toInt(16).toChar()
                            }
                            else -> throw error("invalid escape: \\$esc")
                        }
                    )
                }
                else -> sb.append(c)
            }
        }
        throw error("unterminated string")
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
        return src.substring(start, pos).toDouble()
    }

    private fun parseBoolean(): Boolean = when {
        src.startsWith("true", pos) -> { pos += 4; true }
        src.startsWith("false", pos) -> { pos += 5; false }
        else -> throw error("invalid literal")
    }

    private fun parseNull(): Nothing? = when {
        src.startsWith("null", pos) -> { pos += 4; null }
        else -> throw error("invalid literal")
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char {
        if (pos >= src.length) throw error("unexpected end of input")
        return src[pos]
    }

    private fun next(): Char {
        if (pos >= src.length) throw error("unexpected end of input")
        return src[pos++]
    }

    private fun expect(expected: Char) {
        val c = next()
        if (c != expected) throw error("expected '$expected' but got '$c'")
    }

    private fun error(msg: String): Nothing =
        throw IllegalArgumentException("JSON parse error at $pos: $msg")
}

/** Convenience: parse a JSON string into plain Maps/Lists/values. */
fun parseJson(src: String): Any? = JsonParser(src).parse()
