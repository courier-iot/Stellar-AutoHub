package com.stellar.autohub.editor

/** A problem found before committing. [error] = will almost surely break the build. */
class CheckIssue(val path: String, val line: Int, val message: String, val error: Boolean = true)

/**
 * Cheap sanity checks that catch the classic "one missing brace burns a whole build" mistakes:
 * balanced brackets for Kotlin/Java/Gradle, well-formed XML, valid JSON and YAML.
 */
object Checks {
    /** Set on Android to a real YAML parser (SnakeYAML). Returns (line, message) or null if valid. */
    var yamlValidator: ((String) -> Pair<Int, String>?)? = null

    /** Set on Android to an XML parser. Returns (line, message) or null if valid. */
    var xmlValidator: ((String) -> Pair<Int, String>?)? = null

    fun check(path: String, text: String): List<CheckIssue> {
        val n = path.lowercase()
        val out = mutableListOf<CheckIssue>()
        when {
            n.endsWith(".kt") || n.endsWith(".kts") -> brackets(text, kotlin = true)?.let { out += CheckIssue(path, it.first, it.second) }
            n.endsWith(".java") || n.endsWith(".gradle") -> brackets(text, kotlin = false)?.let { out += CheckIssue(path, it.first, it.second) }
            n.endsWith(".json") -> json(text)?.let { out += CheckIssue(path, it.first, "Invalid JSON: ${it.second}") }
            n.endsWith(".yml") || n.endsWith(".yaml") -> {
                yamlTabs(text)?.let { out += CheckIssue(path, it, "YAML can't use tabs for indentation. Use spaces.") }
                if (out.isEmpty()) yamlValidator?.invoke(text)?.let { out += CheckIssue(path, it.first, "Invalid YAML: ${it.second}") }
            }
            n.endsWith(".xml") -> xmlValidator?.invoke(text)?.let { out += CheckIssue(path, it.first, "Invalid XML: ${it.second}") }
        }
        return out
    }

    private fun yamlTabs(text: String): Int? {
        text.lineSequence().forEachIndexed { i, l -> if (l.takeWhile { it == ' ' || it == '\t' }.contains('\t')) return i + 1 }
        return null
    }

    private const val CODE = 0
    private const val STR = 1
    private const val RAW = 2

    /**
     * Returns (line, message) for the first bracket problem, or null.
     * Understands comments, strings, char literals, Kotlin raw strings and ${...} templates.
     */
    fun brackets(text: String, kotlin: Boolean): Pair<Int, String>? {
        data class Open(val ch: Char, val line: Int, val returnMode: Int = -1)
        val stack = ArrayList<Open>()
        var mode = CODE
        var line = 1
        var i = 0
        var blockDepth = 0
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (c == '\n') line++
            when {
                blockDepth > 0 -> {
                    if (c == '*' && next == '/') { blockDepth--; i++ }
                    else if (kotlin && c == '/' && next == '*') { blockDepth++; i++ }
                }
                mode == STR -> when {
                    c == '\\' -> i++
                    c == '"' -> mode = CODE
                    c == '\n' -> return line - 1 to "String not closed (missing \")."
                    kotlin && c == '$' && next == '{' -> { stack += Open('{', line, STR); mode = CODE; i++ }
                }
                mode == RAW -> when {
                    c == '"' && text.startsWith("\"\"\"", i) -> {
                        var j = i + 3
                        while (j < text.length && text[j] == '"') j++ // """" allowed at end
                        mode = CODE; i = j - 1
                    }
                    c == '$' && next == '{' -> { stack += Open('{', line, RAW); mode = CODE; i++ }
                }
                else -> when {
                    c == '/' && next == '/' -> { while (i < text.length && text[i] != '\n') i++; continue }
                    c == '/' && next == '*' -> { blockDepth = 1; i++ }
                    kotlin && text.startsWith("\"\"\"", i) -> { mode = RAW; i += 2 }
                    c == '"' -> mode = STR
                    c == '\'' -> {
                        // char literal ('a', '\n') or a Groovy 'string': skip to the closing quote on this line
                        var j = i + 1
                        while (j < text.length && text[j] != '\'' && text[j] != '\n') { if (text[j] == '\\') j++; j++ }
                        if (j < text.length && text[j] == '\'') i = j
                    }
                    c == '(' || c == '[' || c == '{' -> stack += Open(c, line)
                    c == ')' || c == ']' || c == '}' -> {
                        val top = stack.lastOrNull()
                            ?: return line to "Extra '$c' with nothing to close."
                        if (top.ch != pairs[c]) {
                            return line to "Found '$c' but the '${top.ch}' opened on line ${top.line} isn't closed yet."
                        }
                        stack.removeAt(stack.size - 1)
                        if (top.returnMode >= 0) mode = top.returnMode
                    }
                }
            }
            i++
        }
        if (blockDepth > 0) return line to "Comment /* … not closed."
        if (mode == STR) return line to "String not closed (missing \")."
        if (mode == RAW) return line to "Raw string \"\"\" not closed."
        val top = stack.lastOrNull() ?: return null
        val close = when (top.ch) { '(' -> ')'; '[' -> ']'; else -> '}' }
        return top.line to "'${top.ch}' opened here is never closed (missing '$close')."
    }

    /** Minimal JSON validator with line numbers. */
    fun json(text: String): Pair<Int, String>? {
        var i = 0
        fun line() = text.substring(0, i.coerceAtMost(text.length)).count { it == '\n' } + 1
        fun ws() { while (i < text.length && text[i].isWhitespace()) i++ }
        class Bad(msg: String) : Exception(msg)
        lateinit var parseValue: () -> Unit
        fun str() {
            i++ // "
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\') i++
                if (i < text.length && text[i] == '\n') throw Bad("line break inside a string")
                i++
            }
            if (i >= text.length) throw Bad("string not closed")
            i++
        }
        fun obj() {
            i++; ws()
            if (i < text.length && text[i] == '}') { i++; return }
            while (true) {
                ws()
                if (i >= text.length || text[i] != '"') throw Bad("expected a \"key\"")
                str(); ws()
                if (i >= text.length || text[i] != ':') throw Bad("expected ':' after key")
                i++; parseValue(); ws()
                if (i < text.length && text[i] == ',') { i++; ws(); if (i < text.length && text[i] == '}') throw Bad("trailing comma"); continue }
                if (i < text.length && text[i] == '}') { i++; return }
                throw Bad("expected ',' or '}'")
            }
        }
        fun arr() {
            i++; ws()
            if (i < text.length && text[i] == ']') { i++; return }
            while (true) {
                parseValue(); ws()
                if (i < text.length && text[i] == ',') { i++; ws(); if (i < text.length && text[i] == ']') throw Bad("trailing comma"); continue }
                if (i < text.length && text[i] == ']') { i++; return }
                throw Bad("expected ',' or ']'")
            }
        }
        parseValue = {
            ws()
            if (i >= text.length) throw Bad("unexpected end")
            when (val c = text[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                else -> {
                    val m = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?|true|false|null""").find(text, i)
                    if (m == null || m.range.first != i) throw Bad("unexpected '$c'")
                    i = m.range.last + 1
                }
            }
        }
        return try {
            parseValue(); ws()
            if (i < text.length) throw Bad("extra content after the end")
            null
        } catch (e: Bad) {
            line() to (e.message ?: "invalid")
        }
    }
}
