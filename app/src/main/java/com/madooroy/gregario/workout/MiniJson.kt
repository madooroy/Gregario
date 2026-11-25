package com.madooroy.gregario.workout

/** Tiny JSON reader (objects -> Map, arrays -> List, numbers -> Double), so the core has no dependencies. */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); require(i == s.length) { "Trailing data at $i" }; v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "Bad literal at $i" }; i += w.length; return v }
        fun num(): Double { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; return s.substring(st, i).toDouble() }
        fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
        fun arr(): List<Any?> {
            val out = mutableListOf<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out += value(); ws(); if (s[i] == ',') { i++; continue }; require(s[i] == ']'); i++; return out }
        }
        fun obj(): Map<String, Any?> {
            val out = linkedMapOf<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; require(s[i] == '}'); i++; return out
            }
        }
    }
}
