package com.stellar.autohub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.editor.*
import com.stellar.autohub.editor.EditorSession.Source
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Code editor. [source] REPO = a file on the repo's current branch (edits become drafts),
 * UPLOAD = a file inside the pending upload (edits go back into the preview).
 */
@Composable
fun EditorScreen(
    repoId: String,
    path: String,
    line: Int,
    source: Source,
    vm: AppViewModel,
    onBack: () -> Unit,
    openChanges: () -> Unit,
    openDiff: () -> Unit,
) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    if (repo == null) { Screen("Editor", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    // Open (or switch to) the requested file
    LaunchedEffect(path, source) {
        loading = true; error = null
        val existing = EditorSession.find(source, repoId, repo.branch, path)
        if (existing != null) {
            EditorSession.currentKey = existing.key
            if (line > 0) existing.jumpLine = line
            loading = false
            return@LaunchedEffect
        }
        try {
            val doc = when (source) {
                Source.UPLOAD -> {
                    val t = vm.payloadText(path) ?: throw IllegalStateException("This file isn't text, so it can't be edited here.")
                    EditorSession.Doc(repoId, repo.branch, path, source, null, t, t)
                }
                Source.REPO -> {
                    val entry = app.blobs.tree(repo).find { it.path == path && it.type == "blob" }
                    val original = entry?.sha?.let { sha ->
                        app.blobs.text(repo, sha) ?: throw IllegalStateException("This file isn't text, so it can't be edited here.")
                    } ?: ""
                    val draft = Drafts.get(repoId, repo.branch, path)
                    EditorSession.Doc(repoId, repo.branch, path, source, entry?.sha, original, draft?.content ?: original, isNew = entry == null)
                }
            }
            EditorSession.open(doc).also { if (line > 0) it.jumpLine = line }
        } catch (e: Exception) { error = e.message ?: "Couldn't open the file" }
        loading = false
    }

    val doc = EditorSession.current()?.takeIf { it.repoId == repoId }
    val tabs = EditorSession.docs.filter { it.repoId == repoId }
    val drafts by Drafts.all.collectAsState()
    val draftCount = drafts.count { it.repoId == repoId && it.branch == repo.branch }

    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var currentHit by remember { mutableIntStateOf(-1) }
    var goTo by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(app.prefs.editorWrap) }
    var mdPreview by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val vScroll = rememberScrollState()

    val text = doc?.text ?: ""
    val hits = remember(text, query, matchCase) {
        if (query.isEmpty()) emptyList() else {
            val out = ArrayList<TextRange>()
            var i = text.indexOf(query, 0, !matchCase)
            while (i >= 0 && out.size < 2000) { out += TextRange(i, i + query.length); i = text.indexOf(query, i + query.length, !matchCase) }
            out
        }
    }
    var issues by remember { mutableStateOf<List<CheckIssue>>(emptyList()) }

    fun scrollToOffset(offset: Int) {
        val l = layout ?: return
        val y = l.getLineTop(l.getLineForOffset(offset.coerceIn(0, l.layoutInput.text.length)))
        scope.launch { vScroll.animateScrollTo((y - 200).toInt().coerceAtLeast(0)) }
    }
    fun select(i: Int) {
        val d = doc ?: return
        if (hits.isEmpty()) return
        currentHit = ((i % hits.size) + hits.size) % hits.size
        val r = hits[currentHit]
        d.value = d.value.copy(selection = r)
        scrollToOffset(r.start)
    }

    // Jump to a requested line once the layout exists
    LaunchedEffect(doc?.jumpLine, layout != null) {
        val d = doc ?: return@LaunchedEffect
        val ln = d.jumpLine ?: return@LaunchedEffect
        if (layout == null) return@LaunchedEffect
        val off = EditorSession.offsetOfLine(d.text, ln)
        d.value = d.value.copy(selection = TextRange(off))
        scrollToOffset(off)
        d.jumpLine = null
    }

    // Auto-save drafts + run checks (debounced)
    LaunchedEffect(doc?.key, text) {
        val d = doc ?: return@LaunchedEffect
        delay(600)
        issues = Checks.check(d.path, d.text)
        if (d.source == Source.REPO) {
            if (d.changed) Drafts.put(ctx, Draft(d.repoId, d.branch, d.path, d.baseSha, d.text))
            else if (Drafts.get(d.repoId, d.branch, d.path)?.let { !it.deleted } == true) Drafts.discard(ctx, d.repoId, d.branch, d.path)
        }
    }

    val isMd = path.endsWith(".md", true) || doc?.path?.endsWith(".md", true) == true

    Screen(
        title = doc?.path?.substringAfterLast('/') ?: path.substringAfterLast('/'),
        onBack = onBack,
        actions = {
            if (doc != null) {
                TextButton(enabled = doc.canUndo, onClick = { doc.undo() }) { Text("Undo") }
                TextButton(enabled = doc.canRedo, onClick = { doc.redo() }) { Text("Redo") }
                IconButton(onClick = { finding = !finding }) { Icon(Icons.Default.Search, "Find") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (isMd) DropdownMenuItem(text = { Text(if (mdPreview) "Edit" else "Preview") }, onClick = { menu = false; mdPreview = !mdPreview })
                    DropdownMenuItem(text = { Text(if (wrap) "Don't wrap lines" else "Wrap lines") }, onClick = {
                        menu = false; wrap = !wrap; app.prefs.editorWrap = wrap
                    })
                    DropdownMenuItem(text = { Text("Go to line…") }, onClick = { menu = false; goTo = true })
                    DropdownMenuItem(text = { Text("View changes (diff)") }, onClick = {
                        menu = false
                        DiffHolder.set("${doc.path}  (your edits)", doc.original, doc.text)
                        openDiff()
                    })
                    DropdownMenuItem(text = { Text("Revert to original") }, onClick = { menu = false; doc.replaceAll(doc.original, 0) })
                    DropdownMenuItem(text = { Text("Close tab") }, onClick = { menu = false; EditorSession.close(doc); if (EditorSession.docs.none { it.repoId == repoId }) onBack() })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (tabs.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOf(doc).coerceAtLeast(0), edgePadding = 8.dp,
                    containerColor = Stellar.Panel, contentColor = Stellar.Cyan,
                ) {
                    tabs.forEach { t ->
                        Tab(selected = t == doc, onClick = { EditorSession.currentKey = t.key }, text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (t.changed) "● " else "") + t.path.substringAfterLast('/'), maxLines = 1)
                                IconButton(onClick = { EditorSession.close(t) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Close, "Close", Modifier.size(14.dp))
                                }
                            }
                        })
                    }
                }
            }
            when {
                loading && doc == null -> Busy("Opening $path…")
                error != null && doc == null -> Box(Modifier.padding(16.dp)) { ErrorBox(error!!) }
                doc != null -> {
                    MonoText("${doc.path} · ${doc.branch}" + if (doc.source == Source.UPLOAD) " · in upload" else "",
                        Stellar.Dim, Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    if (finding) Panel(Modifier.padding(horizontal = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it; currentHit = -1 }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
                            Text(if (hits.isEmpty()) "0" else "${currentHit + 1}/${hits.size}", color = Stellar.Dim, modifier = Modifier.padding(horizontal = 6.dp))
                            TextButton(onClick = { select(currentHit - 1) }) { Text("↑") }
                            TextButton(onClick = { select(currentHit + 1) }) { Text("↓") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(enabled = currentHit in hits.indices, onClick = {
                                val r = hits[currentHit]
                                doc.replaceAll(doc.text.replaceRange(r.start, r.end, replacement), r.start + replacement.length)
                            }) { Text("Replace") }
                            TextButton(enabled = hits.isNotEmpty(), onClick = {
                                val n = hits.size
                                doc.replaceAll(if (matchCase) doc.text.replace(query, replacement) else doc.text.replace(query, replacement, ignoreCase = true), 0)
                                ctx.toast("Replaced $n")
                            }) { Text("All") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(matchCase, { matchCase = it }); Text("Match case", color = Stellar.Dim)
                        }
                    }
                    issues.firstOrNull()?.let { issue ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfoBox("Line ${issue.line}: ${issue.message}", Stellar.Red)
                        }
                        TextButton(onClick = { doc.jumpLine = issue.line }, modifier = Modifier.padding(start = 4.dp)) { Text("Go to line ${issue.line}") }
                    }
                    Box(Modifier.weight(1f)) {
                        if (isMd && mdPreview) MarkdownView(doc.text, Modifier.fillMaxSize())
                        else CodeArea(
                            value = doc.value, onChange = { doc.edit(it) },
                            lang = Highlighter.langFor(doc.path), wrap = wrap, vScroll = vScroll,
                            onLayout = { layout = it }, hits = hits, currentHit = currentHit,
                        )
                    }
                    Surface(color = Stellar.Panel) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ln = EditorSession.lineOfOffset(doc.text, doc.value.selection.start)
                            Text("Ln $ln · ${if (doc.changed) "edited" else "no changes"}", color = Stellar.Dim, modifier = Modifier.weight(1f))
                            if (doc.source == Source.UPLOAD) {
                                Button(enabled = doc.changed, onClick = {
                                    vm.updatePayloadFile(doc.path, doc.text)
                                    EditorSession.close(doc)
                                    ctx.toast("Updated in the upload")
                                    onBack()
                                }) { Text("Apply to upload") }
                            } else {
                                Button(enabled = draftCount > 0, onClick = openChanges) { Text("Changes ($draftCount) →") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (goTo && doc != null) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { goTo = false },
            title = { Text("Go to line") },
            text = { OutlinedTextField(n, { n = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Line number") }) },
            confirmButton = { TextButton(onClick = { goTo = false; n.toIntOrNull()?.let { doc.jumpLine = it } }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancel") } },
        )
    }
}

/** Hands text pairs to the diff screen. */
object DiffHolder {
    class Request(val title: String, val load: suspend () -> Pair<String, String>)
    var request: Request? = null
    fun set(title: String, old: String, new: String) { request = Request(title) { old to new } }
    fun setLoader(title: String, load: suspend () -> Pair<String, String>) { request = Request(title, load) }
}
