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
