package app.reviewnotes.timing.data

/**
 * A small JSON reader (no dependencies) returning Map / List / String / Double / Boolean / null.
 * Data sources here return loose JSON (numbers as strings, JSONP wrappers), so values are read leniently.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run { skipWs(); val v = value(); v }

    /** Strip a JSONP wrapper such as `cb({...})` or `var x={...};` and parse the payload. */
    fun parseLoose(text: String): Any? {
        val s = text.trim()
        val first = s.indexOfFirst { it == '{' || it == '[' }
        if (first < 0) return null
        val open = s[first]
        val close = if (open == '{') '}' else ']'
        val last = s.lastIndexOf(close)
        if (last <= first) return null
        return parse(s.substring(first, last + 1))
    }

    private class Parser(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (val ch = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"', '\'' -> str(ch)
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                val k = if (s[i] == '"' || s[i] == '\'') str(s[i]) else bareKey()
                skipWs(); if (i < s.length && s[i] == ':') i++
                m[k] = value()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                break
            }
            return m
        }
        fun bareKey(): String { val st = i; while (i < s.length && s[i] != ':' && !s[i].isWhitespace()) i++; return s.substring(st, i) }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (i < s.length) {
                l.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                break
            }
            return l
        }
        fun str(q: Char): String {
            val sb = StringBuilder()
            i++
            while (i < s.length) {
                val ch = s[i]
                if (ch == q) { i++; break }
                if (ch == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } }
                        else -> sb.append(n)
                    }
                    i += 2
                } else { sb.append(ch); i++ }
            }
            return sb.toString()
        }
        fun num(): Any? {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (st == i) { i++; return null }
            return s.substring(st, i).toDoubleOrNull()
        }
    }
}

// lenient accessors
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?>? = this as? List<Any?>
fun Any?.str(): String? = when (this) { null -> null; is String -> this; is Double -> if (this % 1.0 == 0.0 && kotlin.math.abs(this) < 1e15) this.toLong().toString() else this.toString(); else -> toString() }
fun Any?.num(): Double = when (this) { is Double -> this; is String -> this.trim().toDoubleOrNull() ?: Double.NaN; is Boolean -> if (this) 1.0 else 0.0; else -> Double.NaN }
@Suppress("UNCHECKED_CAST")
operator fun Any?.get(key: String): Any? = (this as? Map<String, Any?>)?.let { m -> m[key] }
@Suppress("UNCHECKED_CAST")
operator fun Any?.get(index: Int): Any? = (this as? List<Any?>)?.let { l -> if (index in l.indices) l[index] else null }
