package com.stellar.autohub.upload

/** A possible secret found in a file that's about to be committed. */
class Leak(val path: String, val line: Int, val kind: String, val preview: String)

/**
 * Looks for things that should never be pushed to a repo: tokens, private keys, API keys,
 * and passwords sitting in config files. Only files that are new or changed are scanned.
 */
object LeakScanner {

    private class Rule(val kind: String, val regex: Regex)

    private val rules = listOf(
        Rule("GitHub token", Regex("""\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})""")),
        Rule("Private key", Regex("""-----BEGIN (RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY( BLOCK)?-----""")),
        Rule("AWS access key", Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b""")),
        Rule("Google API key", Regex("""\bAIza[0-9A-Za-z_\-]{35}\b""")),
        Rule("Anthropic API key", Regex("""\bsk-ant-[A-Za-z0-9_\-]{20,}""")),
        Rule("OpenAI API key", Regex("""\bsk-(proj-)?[A-Za-z0-9_\-]{32,}""")),
        Rule("Stripe secret key", Regex("""\b(sk|rk)_live_[0-9A-Za-z]{20,}""")),
        Rule("Slack token", Regex("""\bxox[abposr]-[0-9A-Za-z\-]{10,}""")),
        Rule("Discord bot token", Regex("""\b[MN][A-Za-z\d]{23,25}\.[\w-]{6}\.[\w-]{27,}""")),
        Rule("Telegram bot token", Regex("""\b\d{8,10}:AA[0-9A-Za-z_\-]{33}\b""")),
    )

    /** key = value lines in config files. */
    private val configSecret = Regex(
        """(?i)^\s*["']?[\w.\-]*(password|passwd|pwd|secret|token|api[_\-]?key|private[_\-]?key|access[_\-]?key)[\w.\-]*["']?\s*[=:]\s*["']?([^\s"'#,]{6,})"""
    )
    private val configExts = listOf(".properties", ".env", ".ini", ".cfg", ".conf", ".toml", ".yaml", ".yml", ".json", ".xml")

    /** Values that are clearly placeholders or references, not real secrets. */
    private fun looksLikeReference(v: String): Boolean {
        val l = v.lowercase()
        return v.startsWith("\${") || v.startsWith("$") || v.startsWith("{{") || v.startsWith("%") ||
            l.contains("getenv") || l.contains("secrets.") || l.contains("env.") ||
            l in setOf("changeme", "password", "your_password", "yourpassword", "placeholder", "xxxxxx", "******", "true", "false", "null", "none") ||
            l.all { it == 'x' || it == '*' }
    }

    private val riskyNames = listOf(
        Regex("""(^|/)\.env(\.[\w.-]+)?$""") to "Environment file",
        Regex("""(^|/)id_(rsa|ed25519|ecdsa|dsa)$""") to "SSH private key",
        Regex("""\.(pem|p12|pfx|key)$""", RegexOption.IGNORE_CASE) to "Key/certificate file",
        Regex("""(^|/)google-services\.json$""") to "Firebase config",
        Regex("""(^|/)credentials(\.json)?$""") to "Credentials file",
    )

    fun mask(s: String): String = if (s.length <= 8) "••••" else s.take(6) + "••••" + s.takeLast(2)

    fun scan(files: List<PlannedFile>): List<Leak> {
        val out = mutableListOf<Leak>()
        for (f in files) {
            if (f.status == FileStatus.UNCHANGED) continue
            riskyNames.firstOrNull { it.first.containsMatchIn(f.path) }?.let { (_, kind) ->
                if (!f.path.endsWith(".env.example") && !f.path.endsWith(".env.sample")) out += Leak(f.path, 0, kind, "file name")
            }
            val text = PathRules.asText(f.bytes) ?: continue
            val isConfig = configExts.any { f.path.endsWith(it, true) } && !f.path.startsWith(".github/workflows/")
            var found = 0
            text.lineSequence().forEachIndexed { i, line ->
                if (found >= 5 || line.length > 2000) return@forEachIndexed
                var hit = false
                for (r in rules) {
                    val m = r.regex.find(line) ?: continue
                    out += Leak(f.path, i + 1, r.kind, mask(m.value)); found++; hit = true
                    break
                }
                if (isConfig && !hit) {
                    configSecret.find(line)?.let { m ->
                        val v = m.groupValues[2]
                        if (!looksLikeReference(v)) { out += Leak(f.path, i + 1, "Password/secret in config", mask(v)); found++ }
                    }
                }
            }
        }
        return out
    }
}
