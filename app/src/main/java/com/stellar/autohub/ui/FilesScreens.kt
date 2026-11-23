package com.stellar.autohub.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.Cleanup
import com.stellar.autohub.data.GhTreeEntry
import com.stellar.autohub.editor.*
import com.stellar.autohub.security.Guard
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

private val imageExt = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")

@Composable
fun FileBrowserScreen(
    repoId: String,
    onBack: () -> Unit,
    openFile: (String) -> Unit,
    openSearch: () -> Unit,
    openChanges: () -> Unit,
) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    var entries by remember { mutableStateOf<List<GhTreeEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dir by rememberSaveableString("")
    var refresh by remember { mutableIntStateOf(0) }
    var newFile by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<GhTreeEntry?>(null) }
    var image by remember { mutableStateOf<Pair<String, ImageBitmap?>?>(null) }
    var saveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val drafts by Drafts.all.collectAsState()

    if (repo == null) { Screen("Files", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }
    val myDrafts = drafts.filter { it.repoId == repoId && it.branch == repo.branch }.associateBy { it.path }

    LaunchedEffect(refresh, repo.branch) {
        error = null
        entries = try { app.blobs.tree(repo, force = refresh > 0) } catch (e: Exception) { error = e.message; emptyList() }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val b = saveBytes; saveBytes = null
        if (uri != null && b != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) }; ctx.toast("Saved")
        } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't save") }
    }

    BackHandler(enabled = dir.isNotEmpty()) { dir = dir.substringBeforeLast('/', "") }

    Screen(
        title = if (dir.isEmpty()) "${repo.name} files" else dir.substringAfterLast('/'),
        onBack = { if (dir.isNotEmpty()) dir = dir.substringBeforeLast('/', "") else onBack() },
        actions = {
            IconButton(onClick = openSearch) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") }
        },
        floating = {
            ExtendedFloatingActionButton(onClick = { newFile = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New file") })
        },
    ) { pad ->
        val list = entries
        Column(Modifier.padding(pad).fillMaxSize()) {
            MonoText("/${dir}  ·  ${repo.branch}", Stellar.Dim, Modifier.padding(horizontal = 16.dp))
            if (myDrafts.isNotEmpty()) TextButton(onClick = openChanges, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("${myDrafts.size} unsaved change(s) → review & commit")
            }
            error?.let { Box(Modifier.padding(16.dp)) { ErrorBox(it) } }
            if (list == null) Busy("Loading files…")
            if (list != null) {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val inDir = list.filter { it.path.startsWith(prefix) && it.path.length > prefix.length }
            val folders = inDir.map { it.path.removePrefix(prefix) }.filter { it.contains('/') }.map { it.substringBefore('/') }.toSortedSet()
            val files = inDir.filter { it.type == "blob" && !it.path.removePrefix(prefix).contains('/') }.sortedBy { it.path.lowercase() }
            // New files that only exist as drafts in this folder
            val newHere = myDrafts.values.filter { d ->
                d.baseSha == null && !d.deleted && d.path.startsWith(prefix) && !d.path.removePrefix(prefix).contains('/') && files.none { it.path == d.path }
            }
            LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(folders.toList(), key = { "d-$it" }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { dir = prefix + f }.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Text("📁  $f", color = Stellar.Text)
                    }
                }
                items(newHere, key = { "n-" + it.path }) { d ->
                    Row(Modifier.fillMaxWidth().clickable { openFile(d.path) }.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Text("📄  ${d.path.substringAfterLast('/')}", modifier = Modifier.weight(1f))
                        Chip("NEW", Stellar.Green)
                    }
                }
                items(files, key = { "f-" + it.path }) { f ->
                    val d = myDrafts[f.path]
                    val name = f.path.substringAfterLast('/')
                    val isImage = imageExt.any { name.endsWith(it, true) }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (isImage) {
                                image = name to null
                                scope.launch {
                                    image = try {
                                        val b = app.blobs.bytes(repo, f.sha!!)
                                        name to BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                                    } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't load"); null }
                                }
                            } else openFile(f.path)
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text((if (isImage) "🖼  " else "📄  ") + name, color = if (d?.deleted == true) Stellar.Dim else Stellar.Text)
                            Text(Cleanup.size(f.size ?: 0), color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            d?.deleted == true -> Chip("DELETE", Stellar.Red)
                            d != null -> Chip("EDITED", Stellar.Cyan)
                        }
                        IconButton(onClick = { actionsFor = f }) { Icon(Icons.Default.MoreVert, "Actions") }
                    }
                }
            }
            }
        }
    }

    actionsFor?.let { f ->
        val d = myDrafts[f.path]
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(f.path.substringAfterLast('/')) },
            text = {
                Column {
                    MonoText(f.path, Stellar.Dim)
                    TextButton(onClick = {
                        actionsFor = null
                        scope.launch {
                            try { saveBytes = app.blobs.bytes(repo, f.sha!!); save.launch(f.path.substringAfterLast('/')) }
                            catch (e: Exception) { ctx.toast(e.message ?: "Couldn't download") }
                        }
                    }) { Text("Save to phone") }
                    if (d?.deleted == true) TextButton(onClick = { actionsFor = null; Drafts.discard(ctx, repoId, repo.branch, f.path) }) { Text("Undo delete") }
                    else TextButton(onClick = {
                        actionsFor = null
                        Guard.sensitive(ctx, "Mark ${f.path.substringAfterLast('/')} for deletion") {
                            Drafts.put(ctx, Draft(repoId, repo.branch, f.path, f.sha, null, deleted = true))
                            ctx.toast("Will be deleted when you commit your changes")
                        }
                    }) { Text("Delete file", color = Stellar.Red) }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } },
        )
    }

    if (newFile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFile = false },
            title = { Text("New file") },
            text = {
                Column {
                    Text("In /${dir}", color = Stellar.Dim)
                    OutlinedTextField(name, { name = it.trim() }, label = { Text("File name (folders allowed: a/b.kt)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !name.contains(".."), onClick = {
                    newFile = false
                    val path = (if (dir.isEmpty()) "" else "$dir/") + name.trim('/')
                    Drafts.put(ctx, Draft(repoId, repo.branch, path, null, ""))
                    openFile(path)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFile = false }) { Text("Cancel") } },
        )
    }

    image?.let { (name, bmp) ->
        AlertDialog(
            onDismissRequest = { image = null },
            title = { Text(name) },
            text = { if (bmp == null) Busy("Loading…") else Image(bmp, name, Modifier.fillMaxWidth().heightIn(max = 420.dp)) },
            confirmButton = { TextButton(onClick = { image = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun rememberSaveableString(initial: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
fun DiffScreen(onBack: () -> Unit) {
    val req = remember { DiffHolder.request }
    var lines by remember { mutableStateOf<List<DiffLine>?>(null) }
    var full by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf<List<DiffLine>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(req) {
        if (req == null) return@LaunchedEffect
        try {
            val (o, n) = req.load()
            raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LineDiff.diff(o, n) }
            lines = raw
        } catch (e: Exception) { error = e.message }
    }
    Screen(title = "Changes", onBack = onBack, actions = {
        TextButton(onClick = { full = !full }) { Text(if (full) "Only changes" else "Whole file") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (req == null) Text("Nothing to compare.", Modifier.padding(16.dp))
            if (req != null) MonoText(req.title, Stellar.Dim, Modifier.padding(horizontal = 12.dp))
            error?.let { Box(Modifier.padding(12.dp)) { ErrorBox(it) } }
            val l = lines
            if (req != null && l == null && error == null) Busy("Comparing…")
            if (l != null) {
                val (add, del) = LineDiff.stats(l)
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("+$add", Stellar.Green); Chip("−$del", Stellar.Red)
                }
                if (add + del == 0) Text("No differences.", color = Stellar.Dim, modifier = Modifier.padding(12.dp))
                val shown = if (full) l else LineDiff.compact(l)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown.size) { i -> DiffRow(shown[i]) }
                }
            }
        }
    }
}

@Composable
fun ChangesScreen(repoId: String, vm: AppViewModel, onBack: () -> Unit, openFile: (String) -> Unit, openDiff: () -> Unit, openPreview: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val repo = app.store.repo(repoId)
    val drafts by Drafts.all.collectAsState()
    var tree by remember { mutableStateOf<Map<String, String?>?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    if (repo == null) { Screen("Changes", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }
    val mine = drafts.filter { it.repoId == repoId && it.branch == repo.branch }.sortedBy { it.path }

    LaunchedEffect(repo.branch) {
        tree = try { app.blobs.tree(repo, force = true).filter { it.type == "blob" }.associate { it.path to it.sha } } catch (e: Exception) { null }
    }

    Screen(title = "Changes · ${repo.name}", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edits are saved on this phone until you commit them. Everything below goes into one commit on ${repo.branch}.",
                color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            if (mine.isEmpty()) Panel { Text("No changes. Edit files from the file browser.", color = Stellar.Dim) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mine.forEach { d ->
                    val current = tree?.get(d.path)
                    val conflict = tree != null && current != d.baseSha && !(d.baseSha == null && current == null)
                    Panel(accent = if (conflict) Stellar.Amber else Stellar.Line) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MonoText(d.path, modifier = Modifier.weight(1f))
                            when {
                                d.deleted -> Chip("DELETE", Stellar.Red)
                                d.baseSha == null -> Chip("NEW", Stellar.Green)
                                else -> Chip("EDITED", Stellar.Cyan)
                            }
                        }
                        if (conflict) Text("Changed on GitHub since you started editing. Committing will overwrite that change.",
                            color = Stellar.Amber, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (!d.deleted) TextButton(onClick = { openFile(d.path) }) { Text("Edit") }
                            TextButton(onClick = {
                                DiffHolder.setLoader(d.path) {
                                    val old = d.baseSha?.let { app.blobs.text(repo, it) } ?: ""
                                    old to (d.content ?: "")
                                }
                                openDiff()
                            }) { Text("Diff") }
                            TextButton(onClick = {
                                Drafts.discard(ctx, repoId, repo.branch, d.path)
                                EditorSession.find(EditorSession.Source.REPO, repoId, repo.branch, d.path)?.let { EditorSession.close(it) }
                            }) { Text("Discard", color = Stellar.Red) }
                        }
                    }
                }
            }
            if (mine.isNotEmpty()) {
                Button(onClick = {
                    vm.setPayload(Drafts.payload(repoId, repo.branch))
                    openPreview()
                }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Review & commit ${mine.size} change(s)") }
                TextButton(onClick = { confirmAll = true }) { Text("Discard all", color = Stellar.Red) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Discard all changes?") },
        text = { Text("Your ${mine.size} unsaved edit(s) on ${repo.branch} will be lost.") },
        confirmButton = { TextButton(onClick = {
            confirmAll = false
            Drafts.clear(ctx, repoId, repo.branch, mine.map { it.path }.toSet())
            EditorSession.docs.filter { it.repoId == repoId }.toList().forEach { EditorSession.close(it) }
        }) { Text("Discard", color = Stellar.Red) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
    )
}
