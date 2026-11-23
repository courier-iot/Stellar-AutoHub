package com.stellar.autohub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.ci.LineKind
import com.stellar.autohub.ci.LogParser
import com.stellar.autohub.ci.LogSection
import com.stellar.autohub.data.GhAnnotation
import com.stellar.autohub.data.GhJob
import com.stellar.autohub.data.GhRun
import com.stellar.autohub.ui.theme.Stellar
import com.stellar.autohub.editor.ErrorLocation
import com.stellar.autohub.editor.ErrorLocations
import com.stellar.autohub.security.Guard
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class LogView { ERRORS, STEP, ALL }

@Composable
fun RunScreen(
    repoId: String,
    runId: Long,
    onBack: () -> Unit,
    openSecrets: () -> Unit,
    openEditor: (String, Int) -> Unit = { _, _ -> },
) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var run by remember { mutableStateOf<GhRun?>(null) }
    var jobs by remember { mutableStateOf<List<GhJob>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var poll by remember { mutableIntStateOf(0) }

    var logJob by remember { mutableStateOf<GhJob?>(null) }
    var rawLog by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<LogSection>>(emptyList()) }
    var annotations by remember { mutableStateOf<List<GhAnnotation>>(emptyList()) }
    var logLoading by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(LogView.ERRORS) }
    var missingSecrets by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var contextBusy by remember { mutableStateOf<String?>(null) }

    if (repo == null) { Screen("Build", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    fun loadLog(job: GhJob) {
        logJob = job; logLoading = true; logError = null; rawLog = null
        scope.launch {
            try {
                annotations = app.api.annotations(repo.owner, repo.name, job.id)
                val raw = app.api.jobLog(repo.owner, repo.name, job.id)
                rawLog = raw
                sections = LogParser.sections(raw)
            } catch (e: Exception) {
                logError = e.message
            } finally { logLoading = false }
        }
    }

    // Poll until complete
    LaunchedEffect(runId, poll) {
        while (true) {
            try {
                val r = app.api.run(repo.owner, repo.name, runId)
                run = r
                jobs = app.api.jobs(repo.owner, repo.name, runId)
                error = null
                if (r.status == "completed") {
                    if (r.conclusion != "success" && logJob == null) {
                        jobs.firstOrNull { it.conclusion == "failure" }?.let { loadLog(it) }
                    }
                    if (r.conclusion != "success") {
                        missingSecrets = try { app.secrets.overview(repo).missing.map { it.name } } catch (e: Exception) { emptyList() }
                    }
                    break
                }
            } catch (e: Exception) { error = e.message }
            delay(8000)
        }
    }

    val r = run
    val history = app.store.data.collectAsState().value.history
    val entry = r?.let { rr -> history.find { it.repoId == repoId && it.commitSha == rr.head_sha } }
    val failedSection = remember(sections) { LogParser.failedSection(sections) }
    val locations = remember(sections, annotations) {
        ErrorLocations.all(sections.flatMap { s -> s.lines.filter { it.kind == LineKind.ERROR }.map { it.text } } +
            annotations.mapNotNull { it.message })
    }

    Screen(
        title = r?.display_title ?: "Build",
        onBack = onBack,
        actions = { IconButton(onClick = { poll++ }) { Icon(Icons.Default.Refresh, "Refresh") } },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { e -> item { ErrorBox(e) } }
            if (r == null) { item { Busy("Loading build…") }; return@LazyColumn }
            val st = if (r.status == "completed") r.conclusion else r.status

            item {
                Panel(accent = statusColor(st)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name ?: "Workflow", style = MaterialTheme.typography.titleMedium)
                            MonoText("${r.head_sha.take(7)} · attempt ${r.run_attempt}", Stellar.Dim)
                        }
                        Chip(statusText(st), statusColor(st))
                    }
                    if (r.status != "completed") {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.html_url))) }) { Text("GitHub") }
                        if (r.status != "completed") TextButton(enabled = busy == null, onClick = {
                            Guard.sensitive(ctx, "Cancel this build") {
                                busy = "Cancelling…"
                                scope.launch {
                                    try { app.api.cancelRun(repo.owner, repo.name, r.id); delay(2000); poll++ }
                                    catch (e: Exception) { ctx.toast(e.message ?: "Couldn't cancel") } finally { busy = null }
                                }
                            }
                        }) { Text("Cancel", color = Stellar.Red) }
                        if (r.status == "completed" && r.conclusion != "success") {
                            TextButton(enabled = busy == null, onClick = {
                                busy = "Re-running…"
                                scope.launch {
                                    try {
                                        app.api.rerunFailed(repo.owner, repo.name, r.id)
                                        logJob = null; rawLog = null; sections = emptyList(); annotations = emptyList()
                                        entry?.let { e -> BuildWatcher.start(app, repo.id, repo.fullName, r.head_sha, e.id) }
                                        delay(2500); poll++
                                    } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't re-run") } finally { busy = null }
                                }
                            }) { Text("Re-run failed") }
                        }
                        if (r.status == "completed") {
                            TextButton(enabled = busy == null, onClick = {
                                busy = "Re-running…"
                                scope.launch {
                                    try {
                                        app.api.rerunAll(repo.owner, repo.name, r.id)
                                        logJob = null; rawLog = null; sections = emptyList()
                                        entry?.let { e -> BuildWatcher.start(app, repo.id, repo.fullName, r.head_sha, e.id) }
                                        delay(2500); poll++
                                    } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't re-run") } finally { busy = null }
                                }
                            }) { Text("Re-run all") }
                        }
                    }
                    busy?.let { Busy(it) }
                }
            }

            if (missingSecrets.isNotEmpty()) item {
                Panel(Modifier.clickable { openSecrets() }, accent = Stellar.Amber) {
                    Text("This workflow uses secrets that aren't set: ${missingSecrets.joinToString()}. Tap to add them.", color = Stellar.Amber)
                }
            }

            if (r.status == "completed" && r.conclusion == "success") item {
                InstallPanel(repo, runId = r.id, expectedVersion = entry?.version?.takeIf { it.isNotEmpty() }, refreshKey = r.id)
            }

            // Jobs + steps
            items(jobs, key = { "job-${it.id}" }) { job ->
                val jst = if (job.status == "completed") job.conclusion else job.status
                Panel(accent = statusColor(jst).copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Chip(statusText(jst), statusColor(jst))
                    }
                    Spacer(Modifier.height(6.dp))
                    job.steps.forEach { s ->
                        val (icon, color) = when {
                            s.conclusion == "success" -> "✓" to Stellar.Green
                            s.conclusion == "failure" -> "✗" to Stellar.Red
                            s.conclusion == "skipped" -> "–" to Stellar.Dim
                            s.status == "in_progress" -> "●" to Stellar.Cyan
                            else -> "○" to Stellar.Dim
                        }
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(icon, color = color, modifier = Modifier.width(22.dp))
                            Text(s.name, color = if (s.conclusion == "failure") Stellar.Red else Stellar.Text)
                        }
                    }
                    if (job.status == "completed") {
                        TextButton(onClick = { loadLog(job) }) { Text(if (logJob?.id == job.id) "Reload log" else "View log") }
                    }
                }
            }

            // Log / error tools
            val lj = logJob
            if (lj != null) {
                item(key = "logtools") {
                    Panel(accent = if (lj.conclusion == "failure") Stellar.Red else Stellar.Line) {
                        Label("Log: ${lj.name}", if (lj.conclusion == "failure") Stellar.Red else Stellar.Cyan)
                        if (logLoading) Busy("Downloading log…")
                        logError?.let { Text(it, color = Stellar.Red) }
                        val ann = annotations.filter { it.annotation_level == "failure" }
                        if (ann.isNotEmpty()) {
                            ann.take(8).forEach { a ->
                                MonoText("✗ ${a.message?.trim()?.take(500)}", Stellar.Red, Modifier.padding(bottom = 4.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (rawLog != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(view == LogView.ERRORS, { view = LogView.ERRORS }, { Text("Errors") })
                                FilterChip(view == LogView.STEP, { view = LogView.STEP }, { Text("Failed step") })
                                FilterChip(view == LogView.ALL, { view = LogView.ALL }, { Text("Full") })
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val msg = entry?.message
                                    ctx.copy("Build report", LogParser.report(repo, r, lj, annotations, failedSection, msg))
                                }) { Text("Copy report") }
                                Button(enabled = contextBusy == null, onClick = {
                                    contextBusy = "Collecting code…"
                                    scope.launch {
                                        try {
                                            val text = claudeContext(repo, r, lj, annotations, failedSection, entry?.message, locations)
                                            ctx.copy("Context for Claude", text)
                                            ctx.toast("Copied ${text.length / 1000}k characters: paste it to Claude")
                                        } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't collect context") } finally { contextBusy = null }
                                    }
                                }) { Text(contextBusy ?: "Copy for Claude") }
                                OutlinedButton(onClick = {
                                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                                    val f = File(dir, "${repo.name}-run${r.id}-${lj.name.replace(Regex("[^A-Za-z0-9]"), "_")}.txt")
                                    f.writeText(LogParser.clean(rawLog!!).joinToString("\n"))
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Share log"))
                                }) { Text("Share log") }
                            }
                        }
                    }
                }
                if (locations.isNotEmpty()) item(key = "locs") {
                    Panel(accent = Stellar.Red) {
                        Label("Errors in your code (tap to open)", Stellar.Red)
                        locations.take(12).forEach { e ->
                            Column(Modifier.fillMaxWidth().clickable { openEditor(e.path, e.line) }.padding(vertical = 4.dp)) {
                                MonoText("${e.path}:${e.line}", Stellar.Cyan)
                                if (e.message.isNotBlank()) Text(e.message.take(200), color = Stellar.Text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (rawLog != null) {
                    val lines = when (view) {
                        LogView.ERRORS -> failedSection?.let { LogParser.errorExcerpt(it, 2, 300) }
                            ?: sections.flatMap { it.lines }.filter { it.kind == LineKind.ERROR }
                        LogView.STEP -> (failedSection ?: sections.lastOrNull())?.lines ?: emptyList()
                        LogView.ALL -> sections.flatMap { it.lines }.takeLast(3000)
                    }
                    if (lines.isEmpty()) item { Text("No error lines found. Try Failed step or Full.", color = Stellar.Dim) }
                    if (view == LogView.STEP && failedSection != null) item { Label("Step: ${failedSection.title}", Stellar.Dim) }
                    items(lines, key = { "l-${it.index}" }) { l ->
                        val c = when (l.kind) {
                            LineKind.ERROR -> Stellar.Red
                            LineKind.WARNING -> Stellar.Amber
                            LineKind.HEADER -> Stellar.Cyan
                            LineKind.NORMAL -> Stellar.Text.copy(alpha = 0.8f)
                        }
                        val loc = remember(l.text) { ErrorLocations.find(l.text) }
                        if (loc != null && loc.line > 0) MonoText(l.text, c, Modifier.clickable { openEditor(loc.path, loc.line) }.background(Stellar.Red.copy(alpha = 0.08f)))
                        else MonoText(l.text, c)
                    }
                }
            }
        }
    }
}

/**
 * Everything Claude needs to fix a failed build in one paste: the report, the code around each
 * error (from the exact commit that failed), and the project's file list.
 */
private suspend fun claudeContext(
    repo: com.stellar.autohub.data.RepoProfile, run: GhRun, job: GhJob, annotations: List<GhAnnotation>,
    section: LogSection?, message: String?, locations: List<ErrorLocation>,
): String {
    val app = AutoHubApp.instance
    val sb = StringBuilder(LogParser.report(repo, run, job, annotations, section, message))
    val byFile = locations.groupBy { it.path }.entries.take(6)
    if (byFile.isNotEmpty()) {
        sb.appendLine().appendLine("=== Code at the failing commit (${run.head_sha.take(7)}) ===")
        for ((path, locs) in byFile) {
            val bytes = try { app.api.rawBytes(repo.owner, repo.name, path, run.head_sha) } catch (e: Exception) { null }
            val text = bytes?.let { com.stellar.autohub.upload.PathRules.asText(it) }
            if (text == null) { sb.appendLine().appendLine("--- $path (couldn't load)"); continue }
            val lines = text.lines()
            // Whole file if small, else windows around the errors
            sb.appendLine().appendLine("--- $path (errors on line ${locs.joinToString { "${it.line}" }})")
            val keep = sortedSetOf<Int>()
            if (lines.size <= 250) keep += lines.indices
            else locs.forEach { l -> for (i in (l.line - 25)..(l.line + 25)) if (i - 1 in lines.indices) keep += i - 1 }
            var last = -2
            for (i in keep) {
                if (i != last + 1) sb.appendLine("   ⋯")
                sb.append((i + 1).toString().padStart(5)).append(if (locs.any { it.line == i + 1 }) " >> " else " | ").appendLine(lines[i])
                last = i
            }
        }
    }
    try {
        val tree = app.blobs.tree(repo, force = true).filter { it.type == "blob" && !it.path.contains("/build/") }.map { it.path }
        sb.appendLine().appendLine("=== Project files (${tree.size}) ===")
        tree.take(400).forEach { sb.appendLine(it) }
        if (tree.size > 400) sb.appendLine("… ${tree.size - 400} more")
    } catch (_: Exception) { }
    return if (sb.length > 90_000) sb.substring(0, 90_000) + "\n…(trimmed)" else sb.toString()
}
