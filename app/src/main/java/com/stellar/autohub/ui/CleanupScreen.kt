package com.stellar.autohub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.Cleanup
import com.stellar.autohub.ci.CleanupPlan
import com.stellar.autohub.security.Guard
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

@Composable
fun CleanupScreen(repoId: String, onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var keepRuns by remember { mutableFloatStateOf(10f) }
    var keepReleases by remember { mutableFloatStateOf(5f) }
    var deleteTags by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<CleanupPlan?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (repo == null) { Screen("Clean up", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    fun scan() {
        busy = "Counting runs, artifacts and releases…"; error = null
        scope.launch {
            try { plan = Cleanup.plan(app.api, repo, keepRuns.toInt(), keepReleases.toInt()) }
            catch (e: Exception) { error = e.message } finally { busy = null }
        }
    }
    LaunchedEffect(Unit) { scan() }

    Screen(title = "Clean up · ${repo.name}", onBack = if (busy != null && plan != null && result == null) null else onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Old build runs, artifacts and releases pile up on GitHub and count toward your storage. The newest ones are always kept.",
                color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            error?.let { ErrorBox(it) }
            result?.let { InfoBox(it, Stellar.Green) }

            Panel {
                Label("Keep")
                Text("Newest ${keepRuns.toInt()} build runs (and their artifacts)")
                Slider(keepRuns, { keepRuns = it }, valueRange = 1f..50f, steps = 48, onValueChangeFinished = { scan() })
                Text("Newest ${keepReleases.toInt()} releases")
                Slider(keepReleases, { keepReleases = it }, valueRange = 1f..30f, steps = 28, onValueChangeFinished = { scan() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(deleteTags, { deleteTags = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Also delete the git tags of removed releases")
                }
            }

            busy?.let { Busy(it) }
            plan?.let { p ->
                Panel(accent = Stellar.Amber) {
                    Label("Will delete", Stellar.Amber)
                    Text("${p.runs.size} of ${p.totalRuns} build runs")
                    Text("${p.artifacts.size} artifacts (${Cleanup.size(p.freedBytes)} of ${Cleanup.size(p.totalArtifactBytes)} in use)")
                    Text("${p.releases.size} of ${p.totalReleases} releases" + if (p.releases.isNotEmpty()) ": ${p.releases.take(6).joinToString { it.tag_name }}${if (p.releases.size > 6) "…" else ""}" else "")
                    Spacer(Modifier.height(8.dp))
                    val total = p.runs.size + p.artifacts.size + p.releases.size
                    Button(enabled = busy == null && total > 0, onClick = {
                        Guard.sensitive(ctx, "Delete $total items on GitHub") {
                            scope.launch {
                                try {
                                    result = Cleanup.execute(app.api, repo, p, deleteTags) { busy = it }
                                } catch (e: Exception) { error = e.message } finally { busy = null; scan() }
                            }
                        }
                    }) { Text(if (total == 0) "Nothing to clean" else "Delete $total items") }
                    Text("Deleted releases can't be brought back (the build can make them again).", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
