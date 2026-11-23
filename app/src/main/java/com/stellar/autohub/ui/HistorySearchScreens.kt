package com.stellar.autohub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.data.GhCommitItem
import com.stellar.autohub.editor.*
import com.stellar.autohub.upload.PathRules
import com.stellar.autohub.ui.theme.Mono
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun shortDate(iso: String?): String = try {
    val t = java.time.Instant.parse(iso ?: return "").toEpochMilli(); ago(t)
} catch (e: Exception) { iso ?: "" }

@Composable
fun HistoryScreen(repoId: String, onBack: () -> Unit, openCommit: (String) -> Unit) {
    val app = AutoHubApp.instance
    val repo = app.store.repo(repoId)
    var commits by remember { mutableStateOf<List<GhCommitItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    if (repo == null) { Screen("History", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    LaunchedEffect(page) {
        loading = true
        try {
            val list = app.api.commits(repo.owner, repo.name, repo.branch, page)
            commits = commits + list
            more = list.size == 30
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    Screen(title = "History · ${repo.branch}", onBack = onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { e -> item { ErrorBox(e) } }
            items(commits, key = { it.sha }) { c ->
                Panel(Modifier.clickable { openCommit(c.sha) }) {
                    Text(c.commit.message.lineSequence().first(), maxLines = 2)
                    MonoText("${c.sha.take(7)} · ${c.commit.author?.name ?: "?"} · ${shortDate(c.commit.author?.date)}", Stellar.Dim)
                }
            }
            item {
                when {
                    loading -> Busy("Loading…")
                    more -> TextButton(onClick = { page++ }) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
fun CommitScreen(repoId: String, sha: String, onBack: () -> Unit, openChanges: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var commit by remember { mutableStateOf<GhCommitItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(setOf<String>()) }
    if (repo == null) { Screen("Commit", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    LaunchedEffect(sha) { try { commit = app.api.commitDetail(repo.owner, repo.name, sha) } catch (e: Exception) { error = e.message } }

    /** Puts a file's content from [ref] into Changes as a draft. */
    fun restore(path: String, ref: String, label: String) {
        scope.launch {
            try {
                val bytes = app.api.rawBytes(repo.owner, repo.name, path, ref)
                val current = app.blobs.tree(repo, force = true).find { it.path == path }?.sha
                if (bytes == null) {
                    Drafts.put(ctx, Draft(repoId, repo.branch, path, current, null, deleted = true))
                    ctx.toast("$path didn't exist $label. Marked for deletion in Changes.")
                } else {
                    val text = PathRules.asText(bytes) ?: throw IllegalStateException("Only text files can be restored here.")
                    Drafts.put(ctx, Draft(repoId, repo.branch, path, current, text))
                    EditorSession.find(EditorSession.Source.REPO, repoId, repo.branch, path)?.let { EditorSession.close(it) }
                    ctx.toast("Restored $label version into Changes")
                }
            } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't restore") }
        }
    }

    Screen(title = sha.take(7), onBack = onBack, actions = {
        TextButton(onClick = openChanges) { Text("Changes") }
    }) { pad ->
        val c = commit
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { e -> item { ErrorBox(e) } }
            if (c == null && error == null) item { Busy("Loading commit…") }
            if (c != null) {
                item {
                    Panel(accent = Stellar.Cyan) {
                        Text(c.commit.message)
                        MonoText("${c.commit.author?.name ?: "?"} · ${shortDate(c.commit.author?.date)}", Stellar.Dim)
                        Text("${c.files.size} file(s) · +${c.files.sumOf { it.additions }} −${c.files.sumOf { it.deletions }}", color = Stellar.Dim)
                    }
                }
                items(c.files, key = { it.filename }) { f ->
                    Panel {
                        Row(Modifier.fillMaxWidth().clickable { open = if (f.filename in open) open - f.filename else open + f.filename },
                            verticalAlignment = Alignment.CenterVertically) {
                            MonoText(f.filename, modifier = Modifier.weight(1f))
                            Chip(f.status.uppercase(), when (f.status) { "added" -> Stellar.Green; "removed" -> Stellar.Red; else -> Stellar.Cyan })
                        }
                        Text("+${f.additions} −${f.deletions}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (f.status != "removed") TextButton(onClick = { restore(f.filename, c.sha, "this commit's") }) { Text("Use this version") }
                            c.parents.firstOrNull()?.let { p ->
                                TextButton(onClick = { restore(f.filename, p.sha, "the previous") }) { Text("Undo in this file") }
                            }
                        }
                        if (f.filename in open) {
                            val patch = f.patch
                            if (patch == null) Text("Diff too large or binary.", color = Stellar.Dim)
                            else DiffLines(remember(patch) { LineDiff.fromPatch(patch) })
                        }
                    }
                }
            }
        }
    }
}

class SearchHit(val path: String, val line: Int, val text: String)

private val searchableExt = listOf(
    ".kt", ".kts", ".java", ".xml", ".gradle", ".yml", ".yaml", ".json", ".md", ".txt", ".properties", ".pro",
    ".toml", ".cfg", ".sh", ".html", ".css", ".js", ".ts", ".py", ".c", ".cpp", ".h", ".gitignore",
)

@Composable
fun SearchScreen(repoId: String, onBack: () -> Unit, openFile: (String, Int) -> Unit) {
    val app = AutoHubApp.instance
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    if (repo == null) { Screen("Search", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    fun run() {
        val q = query
        if (q.length < 2) return
        searched = true; hits = emptyList(); progress = "Listing files…"
        scope.launch {
            try {
                val files = app.blobs.tree(repo).filter { e ->
                    e.type == "blob" && (e.size ?: 0) < 400_000 && searchableExt.any { e.path.endsWith(it, true) } &&
                        !e.path.contains("/build/")
                }.take(600)
                val drafts = Drafts.forRepo(repoId, repo.branch).associateBy { it.path }
                val sem = Semaphore(6)
                var done = 0
                val found = java.util.Collections.synchronizedList(ArrayList<SearchHit>())
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    files.map { e ->
                        async {
                            sem.withPermit {
                                val text = drafts[e.path]?.content ?: try { app.blobs.text(repo, e.sha!!) } catch (x: Exception) { null }
                                text?.lineSequence()?.forEachIndexed { i, l ->
                                    if (l.contains(q, !matchCase) && found.size < 500) found += SearchHit(e.path, i + 1, l.trim().take(200))
                                }
                                synchronized(found) { done++ }
                                if (done % 20 == 0) progress = "Searched $done/${files.size} files…"
                            }
                        }
                    }.awaitAll()
                }
                hits = found.sortedWith(compareBy({ it.path }, { it.line }))
                progress = null
            } catch (e: Exception) { progress = "Search failed: ${e.message}" }
        }
    }

    Screen(title = "Search · ${repo.name}", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("Text to find") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = query.length >= 2 && progress == null, onClick = { run() }) { Text("Search") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(matchCase, { matchCase = it }); Text("Match case", color = Stellar.Dim)
                Spacer(Modifier.weight(1f))
                Text("Includes your unsaved changes", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
            progress?.let { Busy(it) }
            if (searched && progress == null) Text("${hits.size}${if (hits.size >= 500) "+" else ""} match(es) in ${hits.map { it.path }.distinct().size} file(s)", color = Stellar.Dim)
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                var lastPath = ""
                hits.forEachIndexed { idx, h ->
                    if (h.path != lastPath) {
                        lastPath = h.path
                        item(key = "p-$idx") { MonoText(h.path, Stellar.Cyan, Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    }
                    item(key = "h-$idx") {
                        Row(Modifier.fillMaxWidth().clickable { openFile(h.path, h.line) }.padding(vertical = 4.dp)) {
                            Text("${h.line}", fontFamily = Mono, color = Stellar.Dim, modifier = Modifier.width(44.dp))
                            Text(markHit(h.text, query, matchCase), fontFamily = Mono, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private fun markHit(text: String, q: String, matchCase: Boolean) = buildAnnotatedString {
    var i = 0
    while (true) {
        val j = text.indexOf(q, i, !matchCase)
        if (j < 0 || q.isEmpty()) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(background = Color(0x66FFC14D), color = Color.White)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
}
