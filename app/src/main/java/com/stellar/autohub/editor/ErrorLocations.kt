package com.stellar.autohub.editor

/** A file + line mentioned in a build error, relative to the repo root. */
data class ErrorLocation(val path: String, val line: Int, val column: Int, val message: String)

object ErrorLocations {
    // Kotlin: e: file:///home/runner/work/Repo/Repo/app/src/Foo.kt:42:10 Unresolved reference
    // Kotlin (old): e: /home/runner/work/Repo/Repo/app/src/Foo.kt: (42, 10): message
    // Java: /home/runner/work/Repo/Repo/app/src/Foo.java:42: error: message
    // AAPT: ERROR: /home/runner/work/Repo/Repo/app/src/main/res/layout/x.xml:12: AAPT: error: message
    private val loc = Regex(
        """(?:file://)?/home/runner/work/[^/\s]+/[^/\s]+/([^\s:()]+\.[A-Za-z]+)(?::(\d+)(?::(\d+))?|: \((\d+), ?(\d+)\))?:?\s*(.*)"""
    )

    fun find(line: String): ErrorLocation? {
        val m = loc.find(line) ?: return null
        val g = m.groupValues
        val ln = g[2].toIntOrNull() ?: g[4].toIntOrNull() ?: 0
        val col = g[3].toIntOrNull() ?: g[5].toIntOrNull() ?: 0
        val msg = g[6].removePrefix("error:").removePrefix("AAPT: error:").trim()
        return ErrorLocation(g[1], ln, col, msg)
    }

    /** Unique locations found in error lines, in order. */
    fun all(lines: List<String>): List<ErrorLocation> {
        val seen = HashSet<String>()
        return lines.mapNotNull { find(it) }.filter { it.line > 0 && seen.add("${it.path}:${it.line}") }
    }
}
