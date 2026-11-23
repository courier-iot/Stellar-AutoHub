package com.stellar.autohub.data

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("client_id", "") ?: ""
        set(v) = p.edit().putString("client_id", v.trim()).apply()

    var login: String
        get() = p.getString("login", "") ?: ""
        set(v) = p.edit().putString("login", v).apply()

    var ignorePatterns: String
        get() = p.getString("ignore", DEFAULT_IGNORE) ?: DEFAULT_IGNORE
        set(v) = p.edit().putString("ignore", v).apply()

    var autoStrip: Boolean
        get() = p.getBoolean("auto_strip", true)
        set(v) = p.edit().putBoolean("auto_strip", v).apply()

    var watchBuilds: Boolean
        get() = p.getBoolean("watch_builds", true)
        set(v) = p.edit().putBoolean("watch_builds", v).apply()

    /** Ask for fingerprint / screen lock when the app opens. */
    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) = p.edit().putBoolean("app_lock", v).apply()

    /** Ask for fingerprint / screen lock before sensitive actions. */
    var confirmSensitive: Boolean
        get() = p.getBoolean("confirm_sensitive", true)
        set(v) = p.edit().putBoolean("confirm_sensitive", v).apply()

    /** Seconds in the background before the app locks again. */
    var lockAfterSec: Int
        get() = p.getInt("lock_after", 60)
        set(v) = p.edit().putInt("lock_after", v).apply()

    var scanSecrets: Boolean
        get() = p.getBoolean("scan_secrets", true)
        set(v) = p.edit().putBoolean("scan_secrets", v).apply()

    var lastCrashSeen: Long
        get() = p.getLong("last_crash_seen", 0)
        set(v) = p.edit().putLong("last_crash_seen", v).apply()

    var lastBackup: Long
        get() = p.getLong("last_backup", 0)
        set(v) = p.edit().putLong("last_backup", v).apply()

    fun exportMap(): Map<String, String> = mapOf(
        "client_id" to clientId, "ignore" to ignorePatterns, "auto_strip" to autoStrip.toString(),
        "watch_builds" to watchBuilds.toString(), "app_lock" to appLock.toString(),
        "confirm_sensitive" to confirmSensitive.toString(), "scan_secrets" to scanSecrets.toString(),
    )

    fun importMap(m: Map<String, String>) {
        m["client_id"]?.let { if (clientId.isBlank()) clientId = it }
        m["ignore"]?.let { ignorePatterns = it }
        m["auto_strip"]?.toBooleanStrictOrNull()?.let { autoStrip = it }
        m["watch_builds"]?.toBooleanStrictOrNull()?.let { watchBuilds = it }
        m["confirm_sensitive"]?.toBooleanStrictOrNull()?.let { confirmSensitive = it }
        m["scan_secrets"]?.toBooleanStrictOrNull()?.let { scanSecrets = it }
    }

    companion object {
        val DEFAULT_IGNORE = listOf(
            "__MACOSX/**",
            "**/.DS_Store",
            "**/Thumbs.db",
            "**/desktop.ini",
            ".git/**",
            ".gradle/**",
            ".idea/**",
            "build/**",
            "*/build/**",
            "local.properties",
            "**/*.jks",
            "**/*.keystore",
        ).joinToString("\n")
    }
}
