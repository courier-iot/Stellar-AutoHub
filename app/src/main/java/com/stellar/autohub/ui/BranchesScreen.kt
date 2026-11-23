package com.stellar.autohub.ui

import android.content.Intent
import android.net.Uri
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
import com.stellar.autohub.data.GhBranch
import com.stellar.autohub.data.GhCheckRun
import com.stellar.autohub.data.GhPull
import com.stellar.autohub.security.Guard
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

@Composable
fun BranchesScreen(repoId: String, onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by app.store.data.collectAsState()
    val repo = data.repos.find { it.id == repoId }
    var tab by remember { mutableIntStateOf(0) }
    var branches by remember { mutableStateOf<List<GhBranch>?>(null) }
    var pulls by remember { mutableStateOf<List<GhPull>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newPr by remember { mutableStateOf(false) }
    var openPr by remember { mutableStateOf<GhPull?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    if (repo == null) { Screen("Branches", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    LaunchedEffect(refresh) {
        error = null
        try {
            if (repo.defaultBranch.isEmpty()) {
                val r = app.api.repo(repo.owner, repo.name)
                app.store.upsertRepo(repo.copy(defaultBranch = r.default_branch))
            }
            branches = app.api.branches(repo.owner, repo.name)
            pulls = app.api.pulls(repo.owner, repo.name)
        } catch (e: Exception) { error = e.message }
    }

    fun switchTo(b: String) {
        app.store.upsertRepo(repo.copy(branch = b))
        app.blobs.invalidate(repo)
        ctx.toast("Now working on $b. Uploads, files and builds use this branch.")
    }

    Screen(title = "Branches · ${repo.name}", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = Stellar.Panel, contentColor = Stellar.Cyan) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Branches") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Pull requests (${pulls?.size ?: 0})") })
            }
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { ErrorBox(it) }
                busy?.let { Busy(it) }
                if (tab == 0) {
                    InfoBox("Working on: ${repo.branch}" + if (repo.branch != repo.baseBranch) "  (main branch: ${repo.baseBranch})" else "", Stellar.Cyan)
                    Text("Tip: put risky changes on a test branch, let it build, then merge it into ${repo.baseBranch} with a pull request. " +
                        "A branch only builds if its workflow runs on every branch. New AutoHub projects do that and only publish releases from ${repo.baseBranch}.",
                        color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { creating = true }) { Text("New branch from ${repo.branch}") }
                    val list = branches
                    if (list == null && error == null) Busy("Loading branches…")
                    list?.sortedByDescending { it.name == repo.baseBranch }?.forEach { b ->
                        Panel(accent = if (b.name == repo.branch) Stellar.Cyan else Stellar.Line) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, style = MaterialTheme.typography.titleMedium)
                                    MonoText(b.commit.sha.take(7), Stellar.Dim)
                                }
                                if (b.name == repo.baseBranch) Chip("MAIN", Stellar.Violet)
                                if (b.name == repo.branch) Chip("CURRENT", Stellar.Cyan)
                            }
                            Row {
                                if (b.name != repo.branch) TextButton(onClick = { switchTo(b.name) }) { Text("Switch to") }
                                if (b.name != repo.baseBranch && !b.protected) TextButton(onClick = { deleting = b.name }) { Text("Delete", color = Stellar.Red) }
                            }
                        }
                    }
                } else {
                    if (repo.branch != repo.baseBranch) Button(onClick = { newPr = true }) { Text("New pull request: ${repo.branch} → ${repo.baseBranch}") }
                    else Text("Switch to a branch other than ${repo.baseBranch} to open a pull request from it.", color = Stellar.Dim)
                    val list = pulls
                    if (list == null && error == null) Busy("Loading pull requests…")
                    if (list != null && list.isEmpty()) Panel { Text("No open pull requests.", color = Stellar.Dim) }
                    list?.forEach { p ->
                        Panel(Modifier, accent = Stellar.Violet) {
                            Text("#${p.number} ${p.title}", style = MaterialTheme.typography.titleMedium)
                            MonoText("${p.head.ref} → ${p.base.ref}", Stellar.Dim)
                            TextButton(onClick = { openPr = p }) { Text("Checks & merge") }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New branch") },
            text = {
                Column {
                    Text("Starts from the latest commit on ${repo.branch}.", color = Stellar.Dim)
                    OutlinedTextField(name, { name = it.trim().replace(' ', '-') }, label = { Text("Branch name, e.g. test-editor") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && Regex("^[\\w./-]+$").matches(name), onClick = {
                    creating = false; busy = "Creating $name…"
                    scope.launch {
                        try {
                            val head = app.api.branchHead(repo.owner, repo.name, repo.branch) ?: throw IllegalStateException("${repo.branch} has no commits yet.")
                            app.api.createBranch(repo.owner, repo.name, name, head)
                            switchTo(name); refresh++
                        } catch (e: Exception) { error = e.message } finally { busy = null }
                    }
                }) { Text("Create & switch") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }

    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete branch $b?") },
            text = { Text("Commits that were never merged will be lost from GitHub's branch list.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    Guard.sensitive(ctx, "Delete branch $b") {
                        scope.launch {
                            try { app.api.deleteBranch(repo.owner, repo.name, b); refresh++ } catch (e: Exception) { error = e.message }
                        }
                    }
                }) { Text("Delete", color = Stellar.Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (newPr) {
        var title by remember { mutableStateOf("Merge ${repo.branch}") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPr = false },
            title = { Text("Pull request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoText("${repo.branch} → ${repo.baseBranch}", Stellar.Dim)
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, label = { Text("Description (optional)") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    newPr = false; busy = "Opening pull request…"
                    scope.launch {
                        try {
                            val p = app.api.createPull(repo.owner, repo.name, repo.branch, repo.baseBranch, title, body)
                            refresh++; tab = 1; openPr = p
                        } catch (e: Exception) { error = e.message } finally { busy = null }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newPr = false }) { Text("Cancel") } },
        )
    }

    openPr?.let { start ->
        var pr by remember(start.number) { mutableStateOf(start) }
        var checks by remember(start.number) { mutableStateOf<List<GhCheckRun>?>(null) }
        var deleteAfter by remember { mutableStateOf(true) }
        LaunchedEffect(start.number) {
            try {
                pr = app.api.pull(repo.owner, repo.name, start.number)
                checks = app.api.checkRuns(repo.owner, repo.name, pr.head.sha)
            } catch (e: Exception) { error = e.message }
        }
        AlertDialog(
            onDismissRequest = { openPr = null },
            title = { Text("#${pr.number} ${pr.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoText("${pr.head.ref} → ${pr.base.ref}", Stellar.Dim)
                    val c = checks
                    when {
                        c == null -> Busy("Checking builds…")
                        c.isEmpty() -> Text("No checks ran on this branch.", color = Stellar.Amber)
                        else -> c.forEach { r ->
                            val st = if (r.status == "completed") r.conclusion else r.status
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, modifier = Modifier.weight(1f)); Chip(statusText(st), statusColor(st))
                            }
                        }
                    }
                    Text(when (pr.mergeable) {
                        true -> "Can be merged ✓"
                        false -> "Has conflicts with ${pr.base.ref}. Fix them on GitHub first."
                        null -> "GitHub is still checking for conflicts…"
                    }, color = if (pr.mergeable == false) Stellar.Red else Stellar.Dim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteAfter, { deleteAfter = it }); Text("Delete ${pr.head.ref} after merging")
                    }
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.html_url))) }) { Text("Open on GitHub") }
                }
            },
            confirmButton = {
                TextButton(enabled = pr.mergeable != false, onClick = {
                    val p = pr
                    Guard.sensitive(ctx, "Merge #${p.number} into ${p.base.ref}") {
                        openPr = null; busy = "Merging…"
                        scope.launch {
                            try {
                                app.api.mergePull(repo.owner, repo.name, p.number, "squash", "${p.title} (#${p.number})")
                                if (deleteAfter) try { app.api.deleteBranch(repo.owner, repo.name, p.head.ref) } catch (_: Exception) { }
                                if (repo.branch == p.head.ref) switchTo(p.base.ref)
                                ctx.toast("Merged into ${p.base.ref} ✓ A build starts on ${p.base.ref}.")
                                refresh++
                            } catch (e: Exception) { error = e.message } finally { busy = null }
                        }
                    }
                }) { Text("Squash & merge") }
            },
            dismissButton = { TextButton(onClick = { openPr = null }) { Text("Close") } },
        )
    }
}
