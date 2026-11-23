package com.stellar.autohub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ui.theme.Stellar

@Composable
fun HomeScreen(
    reLogin: () -> Unit,
    openCrashes: () -> Unit,
    openBackup: () -> Unit,
    openRepo: (String) -> Unit,
    addRepo: () -> Unit,
    openKeys: () -> Unit,
    openSettings: () -> Unit,
    newProject: () -> Unit = {},
) {
    val app = AutoHubApp.instance
    val data by app.store.data.collectAsState()
    val authProblem by app.authProblem.collectAsState()
    val online by app.online.collectAsState()
    val keys by app.vault.keys.collectAsState()
    val queue by com.stellar.autohub.upload.UploadQueue.items.collectAsState()
    val newCrash = remember { app.crashes().firstOrNull()?.let { app.crashTime(it) > app.prefs.lastCrashSeen } ?: false }

    Screen(
        title = "Stellar AutoHub",
        actions = {
            TextButton(onClick = newProject) { Text("+ New app") }
            IconButton(onClick = openKeys) { Icon(Icons.Default.Lock, "Signing keys") }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        },
        floating = {
            ExtendedFloatingActionButton(onClick = addRepo, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add repo") })
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Signed in as ${app.prefs.login.ifBlank { "GitHub user" }}", color = Stellar.Dim)
            }
            if (!online) item { InfoBox("Offline. Reconnecting automatically; uploads can be queued meanwhile.", Stellar.Amber) }
            if (authProblem) item {
                Panel(Modifier.clickable { reLogin() }, accent = Stellar.Red) {
                    Label("Sign-in stopped working", Stellar.Red)
                    Text("GitHub rejected the saved token (revoked or expired). Tap to sign in again. Your repos and keys stay.", color = Stellar.Red)
                }
            }
            item { TokenBanner(reLogin) }
            if (newCrash) item {
                Panel(Modifier.clickable { openCrashes() }, accent = Stellar.Amber) {
                    Text("AutoHub crashed last time. Tap to see the report (and copy it for Claude).", color = Stellar.Amber)
                }
            }
            val waiting = queue.count { it.status == "waiting" }
            val review = queue.count { it.status != "waiting" }
            if (waiting + review > 0) item {
                InfoBox(buildString {
                    if (waiting > 0) append("$waiting upload(s) waiting for a connection. ")
                    if (review > 0) append("$review queued upload(s) need a look (open the repo).")
                }.trim(), Stellar.Cyan)
            }
            if (keys.isNotEmpty() && System.currentTimeMillis() - app.prefs.lastBackup > 30L * 24 * 3600 * 1000) item {
                Panel(Modifier.clickable { openBackup() }, accent = Stellar.Violet) {
                    Text(
                        if (app.prefs.lastBackup == 0L) "Your signing keys only live on this phone. Tap to make an encrypted backup."
                        else "Last backup was over a month ago. Tap to make a fresh one.",
                        color = Stellar.Violet,
                    )
                }
            }
            if (data.repos.isEmpty()) {
                item {
                    Panel(accent = Stellar.Violet) {
                        Text("No repos yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("Add a repo, then upload a zip, files or a folder. AutoHub commits it, watches the build and grabs the APK.", color = Stellar.Dim)
                    }
                }
            }
            items(data.repos, key = { it.id }) { repo ->
                val last = data.history.filter { it.repoId == repo.id }.maxByOrNull { it.time }
                Panel(Modifier.clickable { openRepo(repo.id) }, accent = statusColor(last?.status).copy(alpha = 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(repo.name, style = MaterialTheme.typography.titleMedium)
                            Text("${repo.owner} · ${repo.branch}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                        if (last != null) Chip(statusText(last.status), statusColor(last.status))
                    }
                    if (last != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${last.message.lineSequence().first()} · ${ago(last.time)}",
                            color = Stellar.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Parses GitHub's token-expiry header, e.g. "2026-11-23 02:40:00 UTC". */
fun parseTokenExpiry(v: String): Long? {
    for (p in listOf("yyyy-MM-dd HH:mm:ss z", "yyyy-MM-dd HH:mm:ss Z", "yyyy-MM-dd HH:mm z", "yyyy-MM-dd'T'HH:mm:ssX")) {
        try { java.text.SimpleDateFormat(p, java.util.Locale.US).parse(v.trim())?.let { return it.time } } catch (_: Exception) { }
    }
    return null
}

/** Warns about a personal access token that expires soon (with the exact time) or lacks the workflow scope. */
@Composable
private fun TokenBanner(reLogin: () -> Unit) {
    val app = AutoHubApp.instance
    var dismissed by remember { mutableStateOf(app.prefs.tokenWarnDismissed) }
    val expires = app.api.tokenExpires
    val scopes = app.api.lastScopes
    val t = expires?.let { parseTokenExpiry(it) }
    val left = t?.let { it - System.currentTimeMillis() }
    val renews = remember { app.autoRenews() }
    val text = when {
        renews -> null
        left != null && left <= 0 -> "Your GitHub token has expired. Sign in again."
        left != null && left <= 7L * 24 * 3600 * 1000 && dismissed != expires -> {
            val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(t!!))
            val inText = if (left < 24L * 3600 * 1000) "in ${left / 3_600_000}h ${(left / 60_000) % 60}m" else "in ${left / 86_400_000} day(s)"
            "Your GitHub sign-in expires $inText ($whenText). Sign in again with the code once, " +
                "and AutoHub will renew it automatically from then on."
        }
        scopes != null && scopes.isNotBlank() && !scopes.contains("workflow") ->
            "Your token is missing the 'workflow' scope. Uploads that change .github/workflows will be rejected."
        else -> null
    } ?: return
    Panel(accent = Stellar.Amber) {
        Text(text, color = Stellar.Amber)
        Row {
            TextButton(onClick = reLogin) { Text("Sign in again") }
            if (left != null && left > 0) TextButton(onClick = { app.prefs.tokenWarnDismissed = expires; dismissed = expires }) { Text("Hide", color = Stellar.Dim) }
        }
    }
}
