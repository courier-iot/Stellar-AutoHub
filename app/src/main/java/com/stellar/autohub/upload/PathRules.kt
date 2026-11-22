package com.stellar.autohub.upload

import java.security.MessageDigest

object PathRules {
    /** Cleans a path to repo form: forward slashes, no leading ./ or /, rejects "..". */
    fun normalize(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/").ifEmpty { null }
    }

    fun parsePatterns(text: String): List<Regex> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::globToRegex)

    /**
     * Glob rules: `**` = any depth (incl. none), `*` = anything except '/', `?` = one char.
     * Patterns are matched against the full repo path.
     */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    fun matchesAny(path: String, patterns: List<Regex>) = patterns.any { it.matches(path) }

    /** Git's blob id: sha1("blob <size>\0<bytes>"). Lets us skip files that are already identical. */
    fun gitBlobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray())
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if bytes are valid UTF-8 text without NULs, so it can be sent inline in a tree. */
    fun asText(bytes: ByteArray): String? {
        if (bytes.size > 2_000_000) return null
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val s = dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            if (s.toByteArray(Charsets.UTF_8).contentEquals(bytes)) s else null
        } catch (e: Exception) {
            null
        }
    }

    fun modeFor(path: String): String {
        val n = path.substringAfterLast('/')
        return if (n == "gradlew" || n.endsWith(".sh")) "100755" else "100644"
    }

    private val versionKts = Regex("""versionName\s*=\s*"([^"]+)"""")
    private val versionGroovy = Regex("""versionName\s+["']([^"']+)["']""")
    private val appIdKts = Regex("""applicationId\s*=\s*"([^"]+)"""")
    private val appIdGroovy = Regex("""applicationId\s+["']([^"']+)["']""")

    fun versionName(gradle: String): String? =
        versionKts.find(gradle)?.groupValues?.get(1) ?: versionGroovy.find(gradle)?.groupValues?.get(1)

    fun applicationId(gradle: String): String? =
        appIdKts.find(gradle)?.groupValues?.get(1) ?: appIdGroovy.find(gradle)?.groupValues?.get(1)

    val GRADLE_FILES = listOf("app/build.gradle.kts", "app/build.gradle")

    /** "1.10.2" > "1.9.9". Non-numeric parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
