package com.stellar.autohub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.security.Guard
import com.stellar.autohub.upload.FileStatus
import com.stellar.autohub.upload.UploadMode
import com.stellar.autohub.upload.key
import com.stellar.autohub.ui.theme.Stellar

private enum class Section { ADDED, CHANGED, REQUESTED, DELETED, UNCHANGED, SKIPPED }

@Composable
fun PreviewScreen(
    repoId: String,
    vm: AppViewModel,
    onBack: () -> Unit,
    onCommitted: (String) -> Unit,
    openEditor: (String) -> Unit = {},
    openDiff: () -> Unit = {},
) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val repo = app.store.repo(repoId)
    val payload by vm.payload.collectAsState()
    val st by vm.preview.collectAsState()

    LaunchedEffect(repoId) {
        if (vm.preview.value?.repoId != repoId || vm.preview.value?.plan == null) vm.startPreview(repoId)
    }
    LaunchedEffect(st?.doneHistoryId) { st?.doneHistoryId?.let(onCommitted) }

    var open by remember { mutableStateOf(setOf(Section.ADDED, Section.CHANGED, Section.REQUESTED, Section.DELETED)) }
    var ignoreChecks by remember { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun toggle(s: Section) { open = if (s in open) open - s else open + s }

    Screen(title = "Review upload", onBack = if (st?.committing == true) null else onBack) { pad ->
        val s = st
        if (s?.queuedDone == true) {
            Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(accent = Stellar.Amber) {
                    Label("Queued", Stellar.Amber)
                    Text("Saved on this phone. AutoHub sends it as soon as you're online and lets you know.")
                }
                Button(onClick = onBack) { Text("Done") }
            }
            return@Screen
        }
        if (repo == null || payload == null && s?.doneSha == null) {
            Box(Modifier.padding(pad).padding(16.dp)) { Text("Nothing to preview. Pick files again.", color = Stellar.Dim) }
            return@Screen
        }
        if (s == null || s.loading) {
            Box(Modifier.padding(pad).padding(16.dp)) { Busy("Comparing with ${repo.fullName}…") }
            return@Screen
        }
        val plan = s.effective
        if (plan == null) {
            Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ErrorBox(s.error ?: "Couldn't build a preview")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.startPreview(repoId) }) { Text("Try again") }
                    if (s.offline) Button(onClick = { vm.queueCurrent() }) { Text("Queue upload") }
                }
                if (s.offline) Text(
                    "Queued without a preview, it can only add and update files. Nothing gets deleted. Secret warnings are still checked before it's sent.",
                    color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Screen
        }
        val mirror = s.mode == UploadMode.MIRROR
        val deleteCount = (if (mirror) s.selectedDeletes.size else 0) + plan.requestedDeletes.count { !mirror || it !in s.selectedDeletes }
        val nothing = plan.added + plan.changed + deleteCount == 0
        val unresolved = s.unresolvedLeaks
        val allLeaks = s.plan?.leaks.orEmpty()

        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.queuedId != null) item { InfoBox("Reviewing a queued upload. Committing now sends it and takes it off the queue.", Stellar.Cyan) }
            item {
                Panel(accent = Stellar.Cyan) {
                    Text("${payload?.label ?: ""}  →  ${repo.fullName} @ ${repo.branch}", style = MaterialTheme.typography.titleMedium)
                    plan.version?.let { Text("Version in upload: v$it", color = Stellar.Dim) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("+${plan.added} NEW", Stellar.Green)
                        Chip("~${plan.changed} CHANGED", Stellar.Cyan)
                        Chip("−$deleteCount DELETE", Stellar.Red)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${plan.unchanged} already identical · ${plan.skipped.size} skipped · ${s.excluded.size} excluded",
                        color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Secret warnings
            if (allLeaks.isNotEmpty()) item {
                Panel(accent = if (unresolved.isEmpty()) Stellar.Line else Stellar.Red) {
                    Label(if (unresolved.isEmpty()) "Secret warnings reviewed" else "Possible secrets found", if (unresolved.isEmpty()) Stellar.Dim else Stellar.Red)
                    Text("These look like passwords, tokens or keys. Anything pushed to GitHub stays in its history, even if you delete it later.",
                        color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    allLeaks.forEach { l ->
                        val excluded = l.path in s.excluded
                        val allowed = l.key() in s.allowedLeaks
                        Column(Modifier.padding(vertical = 4.dp)) {
                            MonoText("${l.path}${if (l.line > 0) ":${l.line}" else ""}", if (excluded || allowed) Stellar.Dim else Stellar.Red)
                            Text("${l.kind} · ${l.preview}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { vm.toggleExclude(l.path) }) { Text(if (excluded) "Include file again" else "Don't upload file") }
                                if (!excluded && !allowed) TextButton(onClick = { vm.allowLeak(l.key()) }) { Text("It's fine", color = Stellar.Dim) }
                                if (allowed) Text("Allowed", color = Stellar.Dim, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }

            if (plan.checks.isNotEmpty()) item {
                Panel(accent = Stellar.Red) {
                    Label("Pre-commit checks", Stellar.Red)
                    Text("These will very likely break the build:", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    plan.checks.take(10).forEach { c ->
                        Column(Modifier.fillMaxWidth().clickable { openEditor(c.path) }.padding(vertical = 4.dp)) {
                            MonoText("${c.path}:${c.line}", Stellar.Cyan)
                            Text(c.message, color = Stellar.Text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ignoreChecks, { ignoreChecks = it }); Text("Commit anyway", color = Stellar.Dim)
                    }
                }
            }
            s.bumpTarget?.let { target ->
                item {
                    Panel(accent = Stellar.Amber) {
                        Text(
                            if (s.releaseExists) "Release v${plan.version} already exists, so this build won't publish a new one."
                            else "v${plan.version} is not newer than the latest release (${s.latestRelease}).",
                            color = Stellar.Amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { vm.bumpVersion() }) { Text("Bump to v$target") }
                        Text("Updates versionName and versionCode in ${plan.gradlePath}.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(plan.warnings) { InfoBox(it) }
            s.error?.let { e -> item { ErrorBox(e) } }

            item {
                Panel {
                    Label("Options")
                    if (plan.deletable.isNotEmpty()) {
                        Text("Files in the repo that aren't in this upload:", color = Stellar.Dim)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !mirror, onClick = { vm.setMode(UploadMode.PATCH) }, label = { Text("Keep them (patch)") })
                            FilterChip(selected = mirror, onClick = { vm.setMode(UploadMode.MIRROR) }, label = { Text("Delete them (mirror)") })
                        }
                    } else {
                        Text("No old files to clean up.", color = Stellar.Dim)
                    }
                    plan.canStrip?.let { root ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = plan.strippedRoot != null, onCheckedChange = { vm.setStrip(it) })
                            Spacer(Modifier.width(8.dp))
                            Text("Strip top folder \"$root/\"")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = s.message, onValueChange = vm::setMessage,
                        label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
                    )
                }
            }

            fun header(sec: Section, title: String, count: Int, color: Color) {
                item(key = "h-$sec") {
                    Row(
                        Modifier.fillMaxWidth().clickable { toggle(sec) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (sec in open) "▾ " else "▸ ", color = color)
                        Text("$title ($count)", color = color, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            val added = plan.files.filter { it.status == FileStatus.ADDED }
            val changed = plan.files.filter { it.status == FileStatus.CHANGED }
            val same = plan.files.filter { it.status == FileStatus.UNCHANGED }

            if (added.isNotEmpty()) {
                header(Section.ADDED, "New", added.size, Stellar.Green)
                if (Section.ADDED in open) items(added, key = { "a-" + it.path }) {
                    MonoText("+ ${it.path}", Stellar.Green, Modifier.fillMaxWidth().clickable { fileAction = it.path to false }.padding(vertical = 2.dp))
                }
            }
            if (changed.isNotEmpty()) {
                header(Section.CHANGED, "Changed", changed.size, Stellar.Cyan)
                if (Section.CHANGED in open) items(changed, key = { "c-" + it.path }) {
                    MonoText("~ ${it.path}", Stellar.Cyan, Modifier.fillMaxWidth().clickable { fileAction = it.path to true }.padding(vertical = 2.dp))
                }
            }
            val requestedOnly = plan.requestedDeletes.filter { !mirror || it !in s.selectedDeletes }
            if (requestedOnly.isNotEmpty()) {
                header(Section.REQUESTED, "Delete (you asked)", requestedOnly.size, Stellar.Red)
                if (Section.REQUESTED in open) items(requestedOnly, key = { "r-$it" }) { MonoText("− $it", Stellar.Red) }
            }
            if (mirror && plan.deletable.isNotEmpty()) {
                header(Section.DELETED, "Delete", plan.deletable.size, Stellar.Red)
                if (Section.DELETED in open) {
                    item(key = "delall") {
                        Row {
                            TextButton(onClick = { vm.setAllDeletes(true) }) { Text("Select all") }
                            TextButton(onClick = { vm.setAllDeletes(false) }) { Text("Select none") }
                        }
                    }
                    items(plan.deletable, key = { "d-" + it.path }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleDelete(d.path) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = d.path in s.selectedDeletes, onCheckedChange = { vm.toggleDelete(d.path) })
                            MonoText("− ${d.path}", if (d.path in s.selectedDeletes) Stellar.Red else Stellar.Dim, Modifier.weight(1f))
                            if (d.protected) Chip("PROTECTED", Stellar.Amber)
                        }
                    }
                }
            }
            if (same.isNotEmpty()) {
                header(Section.UNCHANGED, "Identical (not sent)", same.size, Stellar.Dim)
                if (Section.UNCHANGED in open) items(same, key = { "u-" + it.path }) { MonoText("= ${it.path}", Stellar.Dim) }
            }
            if (plan.skipped.isNotEmpty()) {
                header(Section.SKIPPED, "Skipped", plan.skipped.size, Stellar.Amber)
                if (Section.SKIPPED in open) items(plan.skipped, key = { "s-" + it.path }) { MonoText("× ${it.path} (${it.reason})", Stellar.Amber) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                when {
                    s.committing -> Panel(accent = Stellar.Cyan) { Busy(s.progress) }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (deleteCount > 0) Guard.sensitive(ctx, "Delete $deleteCount file(s) from ${repo.name}") { vm.commit() }
                                else vm.commit()
                            },
                            enabled = !nothing && unresolved.isEmpty() && (plan.checks.isEmpty() || ignoreChecks),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(
                                when {
                                    nothing -> "Nothing changed"
                                    unresolved.isNotEmpty() -> "Review ${unresolved.size} secret warning(s) first"
                                    else -> "Commit & build"
                                }
                            )
                        }
                        if (s.canQueue || !nothing && unresolved.isEmpty()) OutlinedButton(
                            onClick = { vm.queueCurrent() }, modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (s.canQueue) "Queue for when I'm online" else "Send later (queue)") }
                    }
                }
            }
        }
    }

    fileAction?.let { (path, changed) ->
        val app2 = AutoHubApp.instance
        val repo2 = app2.store.repo(repoId)
        AlertDialog(
            onDismissRequest = { fileAction = null },
            title = { Text(path.substringAfterLast('/')) },
            text = {
                Column {
                    MonoText(path, Stellar.Dim)
                    if (changed && repo2 != null) TextButton(onClick = {
                        fileAction = null
                        val sha = vm.remoteSha(path)
                        val newText = vm.payloadText(path) ?: "(binary file)"
                        DiffHolder.setLoader("$path  (GitHub → upload)") {
                            val old = sha?.let { app2.blobs.text(repo2, it) } ?: "(binary file)"
                            old to newText
                        }
                        openDiff()
                    }) { Text("See what changes") }
                    if (vm.payloadText(path) != null) TextButton(onClick = { fileAction = null; openEditor(path) }) { Text("Edit before uploading") }
                    else Text("Binary file, can't be edited here.", color = Stellar.Dim)
                }
            },
            confirmButton = { TextButton(onClick = { fileAction = null }) { Text("Close") } },
        )
    }
}
