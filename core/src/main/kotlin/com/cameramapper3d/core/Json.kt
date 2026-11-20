package com.cameramapper3d.core

import java.io.IOException

/**
 * Minimal JSON reader/writer helpers for glTF (the core library has no dependencies).
 * Parsed values are Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 */
internal object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw IOException("Unexpected data after JSON")
        return v
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IOException("Unexpected end of JSON")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IOException("Bad JSON near position $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                skipWs()
                if (s.getOrNull(i) != '"') throw IOException("Expected a key in JSON at $i")
                val k = str()
                skipWs()
                if (s.getOrNull(i) != ':') throw IOException("Expected ':' in JSON at $i")
                i++
                m[k] = value()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IOException("Expected ',' or '}' in JSON at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IOException("Expected ',' or ']' in JSON at $i")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw IOException("Unterminated JSON string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IOException("Bad number in JSON at $start")
        }
    }
}

// Typed access helpers for parsed JSON.
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Any?.num(default: Double = 0.0): Double = (this as? Double) ?: default
internal fun Any?.int(default: Int = -1): Int = (this as? Double)?.toInt() ?: default
