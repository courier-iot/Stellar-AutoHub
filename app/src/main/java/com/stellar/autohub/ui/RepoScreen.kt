package com.stellar.autohub.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.data.GhRun
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.ci.HealthReport
import com.stellar.autohub.ci.WorkflowHealth
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.secrets.SecretsOverview
import com.stellar.autohub.security.Guard
import com.stellar.autohub.upload.UploadQueue
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

@Composable
fun RepoScreen(
    repoId: String,
    vm: AppViewModel,
    onBack: () -> Unit,
    openPreview: () -> Unit,
    openRun: (Long) -> Unit,
    openSecrets: () -> Unit,
    openCleanup: () -> Unit,
) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by app.store.data.collectAsState()
    val repo = data.repos.find { it.id == repoId } ?: run {
        Screen(title = "Repo", onBack = onBack) { pad -> Text("This repo was removed.", Modifier.padding(pad).padding(16.dp)) }
        return
    }
    val history = data.history.filter { it.repoId == repoId }.sortedByDescending { it.time }
    val loading by vm.loadingPayload.collectAsState()

    var targetFolder by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf<List<GhRun>?>(null) }
    var runsError by remember { mutableStateOf<String?>(null) }
    var secrets by remember { mutableStateOf<SecretsOverview?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<HealthReport?>(null) }
    var healthBusy by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<Pair<HistoryEntry, Int>?>(null) }
    var undoBusy by remember { mutableStateOf<String?>(null) }
    val queue by UploadQueue.items.collectAsState()
    val myQueue = queue.filter { it.repoId == repoId }

    LaunchedEffect(repoId, refresh) {
        runsError = null
        try { runs = app.api.recentRuns(repo.owner, repo.name, repo.branch) } catch (e: Exception) { runsError = e.message }
        secrets = try { app.secrets.overview(repo) } catch (e: Exception) { null }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.loadUris(uris, targetFolder, onReady = openPreview, onError = { ctx.toast(it) })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.loadTree(uri, onReady = openPreview, onError = { ctx.toast(it) })
    }

    Screen(
        title = repo.name,
        onBack = onBack,
        actions = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Secrets") }, onClick = { menu = false; openSecrets() })
                DropdownMenuItem(text = { Text("Clean up GitHub storage") }, onClick = { menu = false; openCleanup() })
                DropdownMenuItem(text = { Text("Repo settings") }, onClick = { menu = false; editing = true })
                DropdownMenuItem(text = { Text("Open on GitHub") }, onClick = {
                    menu = false
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${repo.fullName}")))
                })
                DropdownMenuItem(text = { Text("Remove from AutoHub", color = Stellar.Red) }, onClick = { menu = false; confirmRemove = true })
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${repo.owner} · ${repo.branch}", color = Stellar.Dim)

            Panel(accent = Stellar.Cyan) {
                Label("Upload")
                Text("A full-project zip or folder can mirror the repo. Loose files just get added or updated.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = loading == null, modifier = Modifier.weight(1f)) { Text("Zip / files") }
                    OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = loading == null, modifier = Modifier.weight(1f)) { Text("Folder") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetFolder, onValueChange = { targetFolder = it },
                    label = { Text("Loose files go into (blank = repo root)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                loading?.let { Busy(it) }
            }

            secrets?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
                Panel(Modifier.clickable { openSecrets() }, accent = Stellar.Amber) {
                    Label("Missing secrets", Stellar.Amber)
                    Text("The workflow uses ${missing.joinToString { it.name }}, but they aren't set. Tap to fix.", color = Stellar.Amber)
                }
            }

            if (myQueue.isNotEmpty()) {
                Panel(accent = Stellar.Amber) {
                    Label("Queued uploads", Stellar.Amber)
                    myQueue.forEach { q ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(q.message.lineSequence().first(), maxLines = 1)
                            Text(
                                when (q.status) {
                                    "needs_review" -> "Needs review: ${q.error ?: "possible secret"}"
                                    "failed" -> "Failed: ${q.error ?: "?"}"
                                    else -> "Waiting for a connection · ${ago(q.created)}" + (q.error?.let { " · last try: $it" } ?: "")
                                },
                                color = if (q.status == "waiting") Stellar.Dim else Stellar.Amber, style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = { vm.reviewQueued(q, onReady = openPreview, onError = { ctx.toast(it) }) }) { Text("Review") }
                                if (q.status != "needs_review") TextButton(onClick = {
                                    UploadQueue.save(ctx, q.copy(status = "waiting", error = null)); UploadQueue.schedule(ctx)
                                    ctx.toast("Will send as soon as you're online")
                                }) { Text("Send now") }
                                TextButton(onClick = { UploadQueue.remove(ctx, q.id) }) { Text("Remove", color = Stellar.Red) }
                            }
                        }
                    }
                }
            }

            InstallPanel(repo, refreshKey = refresh)

            Panel {
                Label("Workflow health")
                val h = health
                when {
                    healthBusy -> Busy("Checking workflows and action versions…")
                    h == null -> {
                        Text("Finds outdated actions, removed commands and GitHub deprecation warnings before they break builds.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            healthBusy = true
                            scope.launch {
                                try { health = WorkflowHealth.check(app.api, repo) } catch (e: Exception) { ctx.toast(e.message ?: "Check failed") } finally { healthBusy = false }
                            }
                        }) { Text("Check now") }
                    }
                    h.issues.isEmpty() -> Text("All good ✓ (${h.texts.size} workflow file(s), ${h.actionsChecked} action(s) checked)", color = Stellar.Green)
                    else -> {
                        h.issues.forEach { i ->
                            Column(Modifier.padding(vertical = 3.dp)) {
                                Text(i.message, color = if (i.severity == "warn") Stellar.Amber else Stellar.Text, style = MaterialTheme.typography.bodySmall)
                                MonoText("${i.file}${if (i.line > 0) ":${i.line}" else ""}", Stellar.Dim)
                            }
                        }
                        Row {
                            if (h.fixable.isNotEmpty()) Button(onClick = {
                                h.fixPayload()?.let { vm.setPayload(it); openPreview() }
                            }) { Text("Fix ${h.fixable.size} automatically") }
                            TextButton(onClick = { health = null }) { Text("Close") }
                        }
                    }
                }
            }

            Panel {
                Label("Recent builds")
                runsError?.let { Text(it, color = Stellar.Red) }
                val list = runs
                when {
                    list == null && runsError == null -> Busy("Loading…")
                    list != null && list.isEmpty() -> Text("No workflow runs on ${repo.branch} yet.", color = Stellar.Dim)
                    list != null -> list.take(8).forEach { r ->
                        val st = if (r.status == "completed") r.conclusion else r.status
                        Row(
                            Modifier.fillMaxWidth().clickable { openRun(r.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.display_title ?: r.name ?: "Run", maxLines = 1)
                                Text("${r.name ?: ""} · ${r.head_sha.take(7)}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                            }
                            Chip(statusText(st), statusColor(st))
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                Panel {
                    Label("AutoHub uploads")
                    undoBusy?.let { Busy(it) }
                    history.take(15).forEach { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = h.runId > 0) { openRun(h.runId) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(h.message.lineSequence().first(), maxLines = 1)
                                Text(
                                    "+${h.added}  ~${h.changed}  −${h.deleted}  ·  ${h.commitSha.take(7)}  ·  ${ago(h.time)}",
                                    color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Chip(statusText(h.status), statusColor(h.status))
                            TextButton(enabled = undoBusy == null, onClick = {
                                undoBusy = "Checking…"
                                scope.launch {
                                    try { undoTarget = h to app.committer.newerCommits(repo, h.commitSha) }
                                    catch (e: Exception) { ctx.toast(e.message ?: "Couldn't check the branch") }
                                    finally { undoBusy = null }
                                }
                            }) { Text("Undo", color = Stellar.Dim) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    undoTarget?.let { (h, newer) ->
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text("Undo this upload?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\"${h.message.lineSequence().first()}\" (${h.commitSha.take(7)})")
                    Text("AutoHub makes a new commit that puts every file back exactly how it was before this upload. History isn't rewritten, so this can be undone too.", color = Stellar.Dim)
                    if (newer > 0) Text("$newer newer commit(s) came after it. Those changes would be rolled back as well.", color = Stellar.Red)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    undoTarget = null
                    Guard.sensitive(ctx, "Undo upload on ${repo.name}") {
                        undoBusy = "Undoing…"
                        scope.launch {
                            try {
                                val msg = "Undo: ${h.message.lineSequence().first()}"
                                val sha = app.committer.revertTo(repo, h.commitSha, msg)
                                val entry = HistoryEntry(
                                    id = java.util.UUID.randomUUID().toString(), repoId = repo.id, time = System.currentTimeMillis(),
                                    message = msg, commitSha = sha, status = if (app.prefs.watchBuilds) "queued" else "none",
                                )
                                app.store.addHistory(entry)
                                if (app.prefs.watchBuilds) BuildWatcher.start(app, repo.id, repo.fullName, sha, entry.id)
                                ctx.toast("Undone. A new build is starting.")
                                refresh++
                            } catch (e: Exception) { ctx.toast(e.message ?: "Undo failed") } finally { undoBusy = null }
                        }
                    }
                }) { Text(if (newer > 0) "Roll back anyway" else "Undo", color = Stellar.Red) }
            },
            dismissButton = { TextButton(onClick = { undoTarget = null }) { Text("Cancel") } },
        )
    }

    if (editing) RepoSettingsDialog(repo, onDismiss = { editing = false }, onSave = { app.store.upsertRepo(it); editing = false; refresh++ })
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${repo.name}?") },
        text = { Text("Only removes it from AutoHub (and its upload history). Nothing changes on GitHub.") },
        confirmButton = {
            TextButton(onClick = { confirmRemove = false; val id = repo.id; onBack(); app.store.removeRepo(id) }) {
                Text("Remove", color = Stellar.Red)
            }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RepoSettingsDialog(
    repo: com.stellar.autohub.data.RepoProfile,
    onDismiss: () -> Unit,
    onSave: (com.stellar.autohub.data.RepoProfile) -> Unit,
) {
    var branch by remember { mutableStateOf(repo.branch) }
    var pkg by remember { mutableStateOf(repo.packageName) }
    var prot by remember { mutableStateOf(repo.protectedPatterns) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repo settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(branch, { branch = it.trim() }, label = { Text("Branch") }, singleLine = true)
                OutlinedTextField(pkg, { pkg = it.trim() }, label = { Text("App package name") }, singleLine = true)
                OutlinedTextField(prot, { prot = it }, label = { Text("Never delete (one pattern per line)") }, minLines = 3)
                Text("Patterns: * = any name, ** = any folders. e.g. docs/**", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(repo.copy(branch = branch.ifBlank { "main" }, packageName = pkg, protectedPatterns = prot))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
