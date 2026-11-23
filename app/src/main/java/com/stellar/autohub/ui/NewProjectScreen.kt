package com.stellar.autohub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.editor.Templates
import com.stellar.autohub.github.GitHubException
import com.stellar.autohub.upload.Payload
import com.stellar.autohub.upload.PathRules
import com.stellar.autohub.upload.Planner
import com.stellar.autohub.upload.RemoteState
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Task(val label: String) {
    REPO("Create the GitHub repo"),
    SECRETS("Add the signing key as repo secrets"),
    UPLOAD("Upload the starter project"),
    BUILD("Start the first build"),
}

private enum class TaskState { WAITING, RUNNING, DONE, SKIPPED, FAILED }

/**
 * Guided "new app" setup: one decision per page, a review page that says exactly what will happen,
 * then a live checklist while AutoHub does it. If a step fails it can be retried without redoing the rest.
 */
@Composable
fun NewProjectScreen(onBack: () -> Unit, onCreated: (String) -> Unit, openKeys: () -> Unit = {}) {
    val app = AutoHubApp.instance
    val scope = rememberCoroutineScope()
    val keys by app.vault.keys.collectAsState()

    var page by rememberSaveable { mutableIntStateOf(0) }
    var appName by rememberSaveable { mutableStateOf("") }
    var repoName by rememberSaveable { mutableStateOf("") }
    var repoEdited by rememberSaveable { mutableStateOf(false) }
    var pkg by rememberSaveable { mutableStateOf("") }
    var pkgEdited by rememberSaveable { mutableStateOf(false) }
    var isPrivate by rememberSaveable { mutableStateOf(true) }
    var keyId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyChosen by rememberSaveable { mutableStateOf(false) }
    var repoFree by remember { mutableStateOf<Boolean?>(null) }

    // Progress
    val states = remember { mutableStateMapOf<Task, TaskState>() }
    var detail by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<RepoProfile?>(null) }

    LaunchedEffect(keys) { if (!keyChosen) keyId = keys.firstOrNull()?.id }

    // Check the repo name is free (debounced)
    LaunchedEffect(repoName) {
        repoFree = null
        if (repoName.isBlank()) return@LaunchedEffect
        delay(500)
        repoFree = try { app.api.repo(app.prefs.login, repoName); false } catch (e: GitHubException) { if (e.code == 404) true else null } catch (e: Exception) { null }
    }

    val validRepo = Regex("^[A-Za-z0-9._-]{1,100}$").matches(repoName)
    val pages = listOf("Start", "Name", "Repo", "Package", "Signing", "Review", "Creating")
    val last = pages.size - 1

    fun runSetup() {
        running = true; error = null
        scope.launch {
            var task = Task.REPO
            try {
                // 1. Repo (skip if it was already created on an earlier try)
                var profile = created
                if (profile == null) {
                    task = Task.REPO; states[task] = TaskState.RUNNING; detail = "Creating ${app.prefs.login}/$repoName…"
                    val r = app.api.createRepo(repoName, isPrivate, "$appName, made with Stellar AutoHub")
                    profile = RepoProfile(UUID.randomUUID().toString(), r.owner.login, r.name, "main", pkg, defaultBranch = "main")
                    app.store.upsertRepo(profile)
                    created = profile
                }
                states[Task.REPO] = TaskState.DONE

                // 2. Signing secrets: before the first push, so the very first build is signed
                task = Task.SECRETS
                val key = keyId?.let { app.vault.get(it) }
                if (key == null) states[task] = TaskState.SKIPPED
                else if (states[task] != TaskState.DONE) {
                    states[task] = TaskState.RUNNING
                    for ((name, role) in app.secrets.signingMapping(null)) {
                        detail = "Setting $name…"
                        app.secrets.valueFor(role, key)?.let { v -> app.secrets.put(profile, name, v) }
                    }
                    states[task] = TaskState.DONE
                }

                // 3. Starter project
                task = Task.UPLOAD
                var sha: String? = null
                if (states[task] != TaskState.DONE) {
                    states[task] = TaskState.RUNNING; detail = "Preparing files…"
                    val payload = Payload(Templates.files(appName.trim(), pkg, profile.name), "Starter project", structured = true)
                    val remote = app.committer.loadRemote(profile)
                    val plan = Planner.plan(payload, remote, PathRules.parsePatterns(app.prefs.ignorePatterns), emptyList(), false, false)
                    sha = app.committer.commit(profile, plan, emptySet(), "Start $appName with Stellar AutoHub") { detail = it }
                    states[task] = TaskState.DONE
                    val entry = HistoryEntry(UUID.randomUUID().toString(), profile.id, System.currentTimeMillis(),
                        "Start $appName", sha, "1.0.0", plan.added, 0, 0, status = "queued")
                    app.store.addHistory(entry)
                    BuildWatcher.start(app, profile.id, profile.fullName, sha, entry.id)
                }

                // 4. First build: wait until GitHub actually picks it up
                task = Task.BUILD
                states[task] = TaskState.RUNNING; detail = "Waiting for GitHub Actions…"
                val head = sha ?: app.api.branchHead(profile.owner, profile.name, "main")
                var started = false
                for (i in 0 until 30) {
                    if (head != null && app.api.runsForSha(profile.owner, profile.name, head).isNotEmpty()) { started = true; break }
                    delay(4000)
                }
                states[task] = if (started) TaskState.DONE else TaskState.SKIPPED
                detail = if (started) "Building now. You'll get a notification when it's done." else "GitHub hasn't started it yet. It'll show on the repo screen."
            } catch (e: Exception) {
                states[task] = TaskState.FAILED
                error = (e.message ?: "Something went wrong") + when {
                    e.message?.contains("name already exists") == true -> "\nThat repo name is taken. Go back and pick another."
                    task == Task.SECRETS -> "\nYour token needs Secrets permission. You can also skip the key and add it later."
                    else -> ""
                }
            } finally { running = false }
        }
    }

    BackHandler(enabled = page in 1 until last) { page -= 1 }

    Screen(
        title = "New app · ${pages[page]}",
        onBack = when {
            running -> null
            page in 1 until last -> ({ page -= 1 })
            else -> onBack
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            // Step indicator
            if (page in 1 until last) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1 until last).forEach { i ->
                        Box(Modifier.weight(1f).height(4.dp).background(if (i <= page) Stellar.Cyan else Stellar.Line, RoundedCornerShape(2.dp)))
                    }
                }
                Text("Step $page of ${last - 1}", color = Stellar.Dim, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    0 -> {
                        Text("Make a new Android app", style = MaterialTheme.typography.headlineSmall)
                        Text("AutoHub sets up everything needed for a working app that builds itself on GitHub.", color = Stellar.Dim)
                        Panel(accent = Stellar.Cyan) {
                            Label("You'll choose")
                            Bullet("1", "The app's name")
                            Bullet("2", "The GitHub repo it lives in")
                            Bullet("3", "Its package name (its permanent ID on Android)")
                            Bullet("4", "Which signing key to use")
                        }
                        Panel {
                            Label("Then AutoHub will")
                            Bullet("•", "Create the repo on your GitHub account")
                            Bullet("•", "Add your signing key as secrets, so builds can be updated over each other")
                            Bullet("•", "Upload a starter app (Stellar look) and the build + release workflow")
                            Bullet("•", "Start the first build. A few minutes later you can install v1.0.0")
                        }
                        Text("Takes about a minute. Nothing is created until the final review.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("What's the app called?", style = MaterialTheme.typography.titleLarge)
                        Text("This is the name under the icon on your phone. You can change it later.", color = Stellar.Dim)
                        OutlinedTextField(appName, {
                            appName = it
                            if (!repoEdited) repoName = Templates.repoNameFor(if (it.trim().startsWith("Stellar", true)) it else "Stellar $it")
                            if (!pkgEdited) pkg = Templates.packageFor(it.trim().removePrefix("Stellar").removePrefix("stellar").trim())
                        }, label = { Text("App name") }, placeholder = { Text("e.g. Stellar Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    2 -> {
                        Text("Where should the code live?", style = MaterialTheme.typography.titleLarge)
                        Text("A new repo on your GitHub account. Each push to it builds the app.", color = Stellar.Dim)
                        OutlinedTextField(repoName, { repoName = it.trim(); repoEdited = true }, label = { Text("Repo name") }, singleLine = true,
                            isError = repoName.isNotEmpty() && (!validRepo || repoFree == false), modifier = Modifier.fillMaxWidth())
                        Text(
                            when {
                                repoName.isBlank() -> " "
                                !validRepo -> "Use letters, numbers, - _ . only."
                                repoFree == true -> "✓ github.com/${app.prefs.login}/$repoName is free"
                                repoFree == false -> "✗ You already have a repo with that name"
                                else -> "Checking…"
                            },
                            color = when { repoFree == true && validRepo -> Stellar.Green; repoFree == false || !validRepo -> Stellar.Red; else -> Stellar.Dim },
                        )
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (isPrivate) "Private" else "Public")
                                    Text(if (isPrivate) "Only you can see the code." else "Anyone can see the code (secrets stay hidden).",
                                        color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(isPrivate, { isPrivate = it })
                            }
                        }
                    }
                    3 -> {
                        Text("Package name", style = MaterialTheme.typography.titleLarge)
                        Text("Android's permanent ID for the app. Updates only install over an app with the same package name, so it can't really change later.", color = Stellar.Dim)
                        OutlinedTextField(pkg, { pkg = it.trim().lowercase(); pkgEdited = true }, label = { Text("Package") }, singleLine = true,
                            isError = pkg.isNotEmpty() && !Templates.validPackage(pkg), modifier = Modifier.fillMaxWidth())
                        Text(if (Templates.validPackage(pkg)) "✓ Looks good" else "Format: lowercase words separated by dots, like com.stellar.notes",
                            color = if (Templates.validPackage(pkg)) Stellar.Green else Stellar.Amber)
                        if (app.store.data.value.repos.any { it.packageName == pkg }) Text("Another of your repos already uses this package.", color = Stellar.Red)
                    }
                    4 -> {
                        Text("Signing key", style = MaterialTheme.typography.titleLarge)
                        Text("Android only installs an update if it's signed with the same key as the version on your phone. Using your usual key from the start means you never have to uninstall.",
                            color = Stellar.Dim)
                        keys.forEach { k ->
                            Panel(Modifier.clickable { keyId = k.id; keyChosen = true }, accent = if (keyId == k.id) Stellar.Cyan else Stellar.Line) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(keyId == k.id, { keyId = k.id; keyChosen = true })
                                    Column { Text(k.label); Text("alias ${k.alias}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        Panel(Modifier.clickable { keyId = null; keyChosen = true }, accent = if (keyId == null) Stellar.Amber else Stellar.Line) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(keyId == null, { keyId = null; keyChosen = true })
                                Column {
                                    Text("No key for now")
                                    Text("The build uses a temporary debug key. Adding a key later means uninstalling once.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (keys.isEmpty()) OutlinedButton(onClick = openKeys) { Text("Create or import a key first →") }
                    }
                    5 -> {
                        Text("Review", style = MaterialTheme.typography.titleLarge)
                        Panel(accent = Stellar.Cyan) {
                            ReviewRow("App name", appName.trim()) { page = 1 }
                            ReviewRow("Repo", "${app.prefs.login}/$repoName · ${if (isPrivate) "private" else "public"}") { page = 2 }
                            ReviewRow("Package", pkg) { page = 3 }
                            ReviewRow("Signing key", keyId?.let { id -> keys.find { it.id == id }?.label } ?: "None (debug key)") { page = 4 }
                        }
                        Panel {
                            Label("When you tap Create")
                            Task.entries.forEachIndexed { i, t ->
                                Bullet("${i + 1}", if (t == Task.SECRETS && keyId == null) "${t.label} (skipped, no key)" else t.label)
                            }
                        }
                        Text("Version starts at 1.0.0. Bump versionName in app/build.gradle.kts to publish new releases.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        val allDone = Task.entries.all { states[it] == TaskState.DONE || states[it] == TaskState.SKIPPED }
                        Text(if (allDone) "Your app is on its way 🚀" else if (error != null) "Something went wrong" else "Setting things up…",
                            style = MaterialTheme.typography.titleLarge)
                        Panel(accent = if (error != null) Stellar.Red else Stellar.Cyan) {
                            Task.entries.forEach { t ->
                                val st = states[t] ?: TaskState.WAITING
                                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    when (st) {
                                        TaskState.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else -> Text(
                                            when (st) { TaskState.DONE -> "✓"; TaskState.FAILED -> "✗"; TaskState.SKIPPED -> "–"; else -> "○" },
                                            color = when (st) { TaskState.DONE -> Stellar.Green; TaskState.FAILED -> Stellar.Red; else -> Stellar.Dim },
                                            fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(t.label, color = if (st == TaskState.WAITING) Stellar.Dim else Stellar.Text)
                                }
                            }
                            if (detail.isNotBlank()) Text(detail, color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                        error?.let { ErrorBox(it) }
                        if (allDone) Panel(accent = Stellar.Green) {
                            Label("What now", Stellar.Green)
                            Bullet("1", "The first build takes a few minutes. AutoHub notifies you when it's done.")
                            Bullet("2", "Then tap Install on the repo screen to put v1.0.0 on your phone.")
                            Bullet("3", "Edit code in Files, or send me the zip and upload my changes.")
                        }
                    }
                }
            }

            // Bottom buttons
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    0 -> Button(onClick = { page = 1 }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Start") }
                    in 1..4 -> {
                        OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                        val ok = when (page) {
                            1 -> appName.isNotBlank()
                            2 -> validRepo && repoFree != false
                            3 -> Templates.validPackage(pkg)
                            else -> true
                        }
                        Button(onClick = { page++ }, enabled = ok, modifier = Modifier.weight(1f).height(50.dp)) { Text("Next") }
                    }
                    5 -> {
                        OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                        Button(onClick = { page = last; runSetup() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Create") }
                    }
                    else -> {
                        val allDone = Task.entries.all { states[it] == TaskState.DONE || states[it] == TaskState.SKIPPED }
                        when {
                            running -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f).height(50.dp)) { Text("Working…") }
                            allDone -> Button(onClick = { created?.let { onCreated(it.id) } }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Open the app's repo") }
                            error != null -> {
                                if (created == null) OutlinedButton(onClick = { error = null; states.clear(); page = 2 }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Change details") }
                                Button(onClick = { runSetup() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Try again") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(mark: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(mark, color = Stellar.Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(text)
    }
}

@Composable
private fun ReviewRow(label: String, value: String, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            Text(value)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

