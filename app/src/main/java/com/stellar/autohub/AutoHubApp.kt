package com.stellar.autohub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.stellar.autohub.data.Prefs
import com.stellar.autohub.data.SecureStore
import com.stellar.autohub.data.Store
import com.stellar.autohub.github.GitHubApi
import com.stellar.autohub.secrets.KeyVault
import com.stellar.autohub.secrets.SecretsManager
import com.stellar.autohub.upload.Committer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class AutoHubApp : Application() {
    lateinit var prefs: Prefs
    lateinit var secure: SecureStore
    lateinit var store: Store
    lateinit var api: GitHubApi
    lateinit var committer: Committer
    lateinit var vault: KeyVault
    lateinit var secrets: SecretsManager

    @Volatile private var cachedToken: String? = null

    /** Set when GitHub says the token no longer works. */
    val authProblem = MutableStateFlow(false)

    /** Last time the app went to the background (for the app lock timeout). */
    @Volatile var backgroundedAt: Long = 0
    @Volatile var unlocked: Boolean = false

    fun token(): String? = cachedToken ?: secure.token().also {
        cachedToken = it
        if (it == null && secure.hasToken()) authProblem.value = true
    }

    fun hasToken(): Boolean = cachedToken != null || secure.hasToken()

    /** False while the phone has no usable network (e.g. mid VPN / Wi-Fi switch). */
    val online = MutableStateFlow(true)

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun check() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            online.value = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        check()
        try {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // Old connections belong to the previous network (VPN on/off): drop them
                    api.resetConnections(); online.value = true
                }
                override fun onLost(network: android.net.Network) { api.resetConnections(); check() }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    online.value = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        } catch (_: Exception) { }
    }

    fun setToken(t: String?) {
        cachedToken = t
        authProblem.value = false
        if (t == null) secure.clear() else secure.saveToken(t)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        secure = SecureStore(this)
        store = Store(this)
        installCrashHandler()
        api = GitHubApi({ token() }, onUnauthorized = { authProblem.value = true })
        committer = Committer(api)
        watchNetwork()
        vault = KeyVault(this)
        secrets = SecretsManager(api)
        Thread {
            clearCache(olderThanMs = 24L * 3600 * 1000)
            com.stellar.autohub.upload.UploadQueue.refresh(this)
            if (com.stellar.autohub.upload.UploadQueue.items.value.any { it.status == "waiting" })
                com.stellar.autohub.upload.UploadQueue.schedule(this)
        }.start()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Watching builds", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Build results", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    // ---------- Crash log ----------

    val crashDir get() = File(filesDir, "crashes").apply { mkdirs() }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val text = buildString {
                    appendLine("Stellar AutoHub crash report")
                    appendLine("App: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Time: ${java.util.Date()}")
                    appendLine()
                    append(sw.toString().take(20_000))
                }
                File(crashDir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
                crashDir.listFiles()?.sortedByDescending { it.name }?.drop(20)?.forEach { it.delete() }
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }

    fun crashes(): List<File> = crashDir.listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.name } ?: emptyList()

    fun crashTime(f: File): Long = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()

    // ---------- Cache ----------

    private val cacheDirs get() = listOf(File(cacheDir, "apk"), File(cacheDir, "shared"))

    fun cacheBytes(): Long = cacheDirs.sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Removes downloaded APKs / shared logs, all of them or only ones older than [olderThanMs]. */
    fun clearCache(olderThanMs: Long = 0) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        cacheDirs.forEach { d -> d.listFiles()?.forEach { if (olderThanMs == 0L || it.lastModified() < cutoff) it.deleteRecursively() } }
    }

    companion object {
        const val CH_WATCH = "watch"
        const val CH_RESULT = "result"
        lateinit var instance: AutoHubApp
            private set
    }
}
