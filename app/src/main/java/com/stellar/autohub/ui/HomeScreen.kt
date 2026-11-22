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
fun HomeScreen(openRepo: (String) -> Unit, addRepo: () -> Unit, openKeys: () -> Unit, openSettings: () -> Unit) {
    val app = AutoHubApp.instance
    val data by app.store.data.collectAsState()

    Screen(
        title = "Stellar AutoHub",
        actions = {
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
