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

class AutoHubApp : Application() {
    lateinit var prefs: Prefs
    lateinit var secure: SecureStore
    lateinit var store: Store
    lateinit var api: GitHubApi
    lateinit var committer: Committer
    lateinit var vault: KeyVault
    lateinit var secrets: SecretsManager

    @Volatile private var cachedToken: String? = null

    fun token(): String? = cachedToken ?: secure.token().also { cachedToken = it }

    fun setToken(t: String?) {
        cachedToken = t
        if (t == null) secure.clear() else secure.saveToken(t)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        secure = SecureStore(this)
        store = Store(this)
        api = GitHubApi { token() }
        committer = Committer(api)
        vault = KeyVault(this)
        secrets = SecretsManager(api)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Watching builds", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Build results", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_WATCH = "watch"
        const val CH_RESULT = "result"
        lateinit var instance: AutoHubApp
            private set
    }
}
