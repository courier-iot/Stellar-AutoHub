package com.stellar.autohub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.delay

/** Right after a commit: waits for GitHub to start the workflow, then jumps to the run. */
@Composable
fun WatchScreen(repoId: String, historyId: String, onBack: () -> Unit, openRun: (Long) -> Unit) {
    val app = AutoHubApp.instance
    val repo = app.store.repo(repoId)
    val entry = app.store.data.collectAsState().value.history.find { it.id == historyId }
    var waited by remember { mutableIntStateOf(0) }

    LaunchedEffect(historyId) {
        if (repo == null || entry == null) return@LaunchedEffect
        while (waited < 240) {
            val runs = try { app.api.runsForSha(repo.owner, repo.name, entry.commitSha) } catch (e: Exception) { emptyList() }
            if (runs.isNotEmpty()) { openRun(runs.first().id); return@LaunchedEffect }
            delay(5000); waited += 5
        }
    }

    Screen(title = "Committed ✓", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (repo == null || entry == null) {
                Text("Upload not found.", color = Stellar.Dim)
            } else {
                Panel(accent = Stellar.Green) {
                    Label("Pushed to ${repo.branch}", Stellar.Green)
                    Text(entry.message)
                    Spacer(Modifier.height(4.dp))
                    MonoText("${entry.commitSha.take(7)} · +${entry.added} ~${entry.changed} −${entry.deleted}", Stellar.Dim)
                }
                if (waited < 240) Busy("Waiting for GitHub Actions to pick it up…")
                else InfoBox("No workflow started after 4 minutes. Check that the repo has a workflow that runs on push to ${repo.branch}.")
            }
            if (app.prefs.watchBuilds) Text("You can leave. AutoHub notifies you when the build finishes.", color = Stellar.Dim)
        }
    }
}
