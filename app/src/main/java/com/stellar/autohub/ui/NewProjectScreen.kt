package com.stellar.autohub.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.editor.Templates
import com.stellar.autohub.github.GitHubException
import com.stellar.autohub.upload.*
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Task(val label: String) {
    REPO("Create the GitHub repo"),
    SECRETS("Add the signing key as repo secrets"),
    UPLOAD("Upload the project files"),
    BUILD("Start the first build"),
}

private enum class TaskState { WAITING, RUNNING, DONE, SKIPPED, FAILED }

/** What the first commit contains. */
private enum class Contents(val id: String) { ANDROID("android"), ELECTRON("electron"), OWN("own"), EMPTY("empty") }

/**
 * Guided "new app" setup: one decision per page, a review page that says exactly what will happen,
 * then a live checklist. Pages that don't apply (package name, signing key) are skipped automatically.
 */
@Composable
fun NewProjectScreen(vm: AppViewModel, onBack: () -> Unit, onCreated: (String) -> Unit, openKeys: () -> Unit = {}) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by app.vault.keys.collectAsState()
    val own by vm.wizardPayload.collectAsState()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var appName by rememberSaveable { mutableStateOf("") }
    var contentsId by rememberSaveable { mutableStateOf(Contents.ANDROID.id) }
    var repoName by rememberSaveable { mutableStateOf("") }
    var repoEdited by rememberSaveable { mutableStateOf(false) }
    var pkg by rememberSaveable { mutableStateOf("") }
    var pkgEdited by rememberSaveable { mutableStateOf(false) }
    var isPrivate by rememberSaveable { mutableStateOf(true) }
    var keyId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyChosen by rememberSaveable { mutableStateOf(false) }
    var addWorkflow by rememberSaveable { mutableStateOf(true) }
    var repoFree by remember { mutableStateOf<Boolean?>(null) }
    var picking by remember { mutableStateOf(false) }

    val contents = Contents.entries.first { it.id == contentsId }
    val ownKind = remember(own) { own?.let { ProjectKinds.detect(it) } ?: ProjectKind.UNKNOWN }
    val ownHasWorkflow = remember(own) { own?.files?.any { (PathRules.normalize(it.path) ?: "").contains(".github/workflows/") } == true }
    val kind = when (contents) {
        Contents.ANDROID -> ProjectKind.ANDROID
        Contents.ELECTRON -> ProjectKind.ELECTRON
        Contents.OWN -> ownKind
        Contents.EMPTY -> ProjectKind.UNKNOWN
    }
    val needsPackage = contents == Contents.ANDROID || contents == Contents.ELECTRON
    val needsKey = kind.isAndroid

    // Progress
    val states = remember { mutableStateMapOf<Task, TaskState>() }
    var detail by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<RepoProfile?>(null) }

    LaunchedEffect(keys) { if (!keyChosen) keyId = keys.firstOrNull()?.id }

    LaunchedEffect(repoName) {
        repoFree = null
        if (repoName.isBlank()) return@LaunchedEffect
        delay(500)
        repoFree = try { app.api.repo(app.prefs.login, repoName); false } catch (e: GitHubException) { if (e.code == 404) true else null } catch (e: Exception) { null }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking = false
        if (uris.isNotEmpty()) vm.loadWizardFiles(uris, null) { ctx.toast(it) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        picking = false
        if (uri != null) vm.loadWizardFiles(emptyList(), uri) { ctx.toast(it) }
    }

    // Pages that apply to the chosen contents
    val pages = buildList {
        add("Start"); add("Name"); add("Contents"); add("Repo")
        if (needsPackage) add("Package")
        if (needsKey) add("Signing")
        add("Review"); add("Creating")
    }
    val page = pages[step.coerceIn(0, pages.size - 1)]
    val last = pages.size - 1
    fun goTo(name: String) { pages.indexOf(name).takeIf { it >= 0 }?.let { step = it } }

    val validRepo = Regex("^[A-Za-z0-9._-]{1,100}$").matches(repoName)
    val workflowTemplate = Templates.workflowFor(kind, repoName)

    fun buildPayload(profile: RepoProfile): Payload = when (contents) {
        Contents.ANDROID -> Payload(Templates.files(appName.trim(), pkg, profile.name), "Android starter", structured = true)
        Contents.ELECTRON -> Payload(Templates.electronFiles(appName.trim(), pkg, profile.name), "Windows starter", structured = true)
        Contents.EMPTY -> Payload(listOf(RawFile("README.md", "# ${appName.trim()}\n\nMade with Stellar AutoHub.\n".toByteArray())), "Empty", structured = false)
        Contents.OWN -> {
            val p = own ?: throw IllegalStateException("Pick your project files first.")
            val wf = if (!ownHasWorkflow && addWorkflow) Templates.workflowFor(ownKind, profile.name) else null
            if (wf == null) p else {
                // Put the workflow inside the zip's wrapping folder if there is one, so it lands at the repo root after stripping
                val paths = p.files.mapNotNull { PathRules.normalize(it.path) }
                val roots = paths.map { it.substringBefore('/', "") }.toSet()
                val prefix = if (roots.size == 1 && roots.first().isNotEmpty() && paths.all { it.contains('/') }) roots.first() + "/" else ""
                Payload(p.files + RawFile("$prefix.github/workflows/build.yml", wf.toByteArray()), p.label, p.structured)
            }
        }
    }

    fun runSetup() {
        running = true; error = null
        scope.launch {
            var task = Task.REPO
            try {
                var profile = created
                if (profile == null) {
                    task = Task.REPO; states[task] = TaskState.RUNNING; detail = "Creating ${app.prefs.login}/$repoName…"
                    val r = app.api.createRepo(repoName, isPrivate, "$appName, made with Stellar AutoHub")
                    profile = RepoProfile(
                        UUID.randomUUID().toString(), r.owner.login, r.name, "main",
                        if (contents == Contents.ANDROID) pkg else "", defaultBranch = "main", kind = kind.id,
                    )
                    app.store.upsertRepo(profile)
                    created = profile
                }
                states[Task.REPO] = TaskState.DONE

                task = Task.SECRETS
                val key = if (needsKey) keyId?.let { app.vault.get(it) } else null
                if (key == null) states[task] = TaskState.SKIPPED
                else if (states[task] != TaskState.DONE) {
                    states[task] = TaskState.RUNNING
                    for ((name, role) in app.secrets.signingMapping(null)) {
                        detail = "Setting $name…"
                        app.secrets.valueFor(role, key)?.let { v -> app.secrets.put(profile, name, v) }
                    }
                    states[task] = TaskState.DONE
                }

                task = Task.UPLOAD
                var sha: String? = null
                var willBuild = contents != Contents.EMPTY
                if (states[task] != TaskState.DONE) {
                    states[task] = TaskState.RUNNING; detail = "Preparing files…"
                    val payload = buildPayload(profile)
                    val remote = app.committer.loadRemote(profile)
                    val plan = Planner.plan(payload, remote, ProjectKinds.ignoreFor(app.prefs.ignorePatterns, kind), emptyList(),
                        strip = null, autoStrip = true)
                    if (plan.files.isEmpty()) throw IllegalStateException("Nothing to upload: every file was skipped by the ignore rules.")
                    willBuild = plan.files.any { it.path.startsWith(".github/workflows/") }
                    sha = app.committer.commit(profile, plan, emptySet(), "Start $appName with Stellar AutoHub") { detail = it }
                    if (plan.applicationId != null) app.store.upsertRepo(profile.copy(packageName = plan.applicationId))
                    states[task] = TaskState.DONE
                    val entry = HistoryEntry(UUID.randomUUID().toString(), profile.id, System.currentTimeMillis(),
                        "Start $appName", sha, plan.version ?: "", plan.added, 0, 0, status = if (willBuild) "queued" else "none")
                    app.store.addHistory(entry)
                    if (willBuild) BuildWatcher.start(app, profile.id, profile.fullName, sha, entry.id)
                }

                task = Task.BUILD
                if (!willBuild) {
                    states[task] = TaskState.SKIPPED
                    detail = "No build workflow, so nothing builds yet. Repo screen → Workflow health can add one."
                } else {
                    states[task] = TaskState.RUNNING; detail = "Waiting for GitHub Actions…"
                    val head = sha ?: app.api.branchHead(profile.owner, profile.name, "main")
                    var started = false
                    for (i in 0 until 30) {
                        if (head != null && app.api.runsForSha(profile.owner, profile.name, head).isNotEmpty()) { started = true; break }
                        delay(4000)
                    }
                    states[task] = if (started) TaskState.DONE else TaskState.SKIPPED
                    detail = if (started) "Building now. You'll get a notification when it's done." else "GitHub hasn't started it yet. It'll show on the repo screen."
                }
                vm.wizardPayload.value = null
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

    BackHandler(enabled = step in 1 until last) { step -= 1 }

    Screen(
        title = "New app · $page",
        onBack = when {
            running -> null
            step in 1 until last -> ({ step -= 1 })
            else -> onBack
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            if (step in 1 until last) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1 until last).forEach { i ->
                        Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) Stellar.Cyan else Stellar.Line, RoundedCornerShape(2.dp)))
                    }
                }
                Text("Step $step of ${last - 1}", color = Stellar.Dim, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "Start" -> {
                        Text("Make a new app", style = MaterialTheme.typography.headlineSmall)
                        Text("AutoHub creates a GitHub repo, puts your project in it, and sets up automatic builds.", color = Stellar.Dim)
                        Panel(accent = Stellar.Cyan) {
                            Label("You'll choose")
                            Bullet("1", "The app's name")
                            Bullet("2", "What goes in it: a starter app (Android or Windows) or your own files")
                            Bullet("3", "The GitHub repo it lives in")
                            Bullet("4", "For starters: its ID. For Android: the signing key")
                        }
                        Panel {
                            Label("Then AutoHub will")
                            Bullet("•", "Create the repo on your GitHub account")
                            Bullet("•", "Add your signing key as secrets (Android)")
                            Bullet("•", "Upload the files as the first commit, with a build workflow")
                            Bullet("•", "Start the first build. Releases are tagged vX.Y.Z like your other apps")
                        }
                        Text("Nothing is created until the final review.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    }
                    "Name" -> {
                        Text("What's the app called?", style = MaterialTheme.typography.titleLarge)
                        Text("Shown as the app's name. You can change it later.", color = Stellar.Dim)
                        OutlinedTextField(appName, {
                            appName = it
                            if (!repoEdited) repoName = Templates.repoNameFor(it)
                            if (!pkgEdited) pkg = Templates.packageFor(it.trim().removePrefix("Stellar").removePrefix("stellar").trim())
                        }, label = { Text("App name") }, placeholder = { Text("e.g. Mocap Trainer S") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "Contents" -> {
                        Text("What goes in it?", style = MaterialTheme.typography.titleLarge)
                        ChoiceCard(contents == Contents.ANDROID, "Android starter app", "A Kotlin + Compose app with the Stellar look. Builds an APK.") { contentsId = Contents.ANDROID.id }
                        ChoiceCard(contents == Contents.ELECTRON, "Windows starter app (Electron)", "HTML/CSS/JavaScript desktop app. Builds an installer and a portable .exe on GitHub's Windows machines.") { contentsId = Contents.ELECTRON.id }
                        ChoiceCard(contents == Contents.OWN, "My own project files", "Upload a zip or folder you already have as the first commit.") { contentsId = Contents.OWN.id }
                        ChoiceCard(contents == Contents.EMPTY, "Empty repo", "Just a README. Upload files later.") { contentsId = Contents.EMPTY.id }
                        if (contents == Contents.OWN) Panel(accent = Stellar.Violet) {
                            Label("Your files", Stellar.Violet)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { picking = true; pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Zip / files") }
                                OutlinedButton(onClick = { picking = true; pickFolder.launch(null) }, modifier = Modifier.weight(1f)) { Text("Folder") }
                            }
                            val p = own
                            if (p == null) Text("Pick a zip of the project, or its folder (hidden folders like .github are included).", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                            else {
                                Spacer(Modifier.height(6.dp))
                                Text("✓ ${p.label}: ${p.files.size} file(s)", color = Stellar.Green)
                                Text("Looks like: ${ownKind.label}" + if (ownKind.platform != "Any") " (${ownKind.platform})" else "", color = Stellar.Text)
                                if (ownHasWorkflow) Text("Has its own build workflow ✓", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                                else if (Templates.workflowFor(ownKind, repoName) != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(addWorkflow, { addWorkflow = it })
                                    Text("Add the standard ${ownKind.label} build workflow (releases vX.Y.Z from main)")
                                } else Text("No build workflow found, and AutoHub has no template for this kind yet. The code will be stored but not built.",
                                    color = Stellar.Amber, style = MaterialTheme.typography.bodySmall)
                                if (ownKind == ProjectKind.ELECTRON) Text("Version comes from \"version\" in package.json.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    "Repo" -> {
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
                    "Package" -> {
                        val isAndroid = contents == Contents.ANDROID
                        Text(if (isAndroid) "Package name" else "App ID", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (isAndroid) "Android's permanent ID for the app. Updates only install over an app with the same package name, so it can't really change later."
                            else "Windows uses this to recognise the app (installer, taskbar, settings). Keep it the same across versions.",
                            color = Stellar.Dim,
                        )
                        OutlinedTextField(pkg, { pkg = it.trim().lowercase(); pkgEdited = true }, label = { Text(if (isAndroid) "Package" else "App ID") }, singleLine = true,
                            isError = pkg.isNotEmpty() && !Templates.validPackage(pkg), modifier = Modifier.fillMaxWidth())
                        Text(if (Templates.validPackage(pkg)) "✓ Looks good" else "Format: lowercase words separated by dots, like com.stellar.notes",
                            color = if (Templates.validPackage(pkg)) Stellar.Green else Stellar.Amber)
                        if (isAndroid && app.store.data.value.repos.any { it.packageName == pkg }) Text("Another of your repos already uses this package.", color = Stellar.Red)
                    }
                    "Signing" -> {
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
                    "Review" -> {
                        Text("Review", style = MaterialTheme.typography.titleLarge)
                        Panel(accent = Stellar.Cyan) {
                            ReviewRow("App name", appName.trim()) { goTo("Name") }
                            ReviewRow("Contents", when (contents) {
                                Contents.ANDROID -> "Android starter app"
                                Contents.ELECTRON -> "Windows starter app (Electron)"
                                Contents.OWN -> "${own?.label ?: "?"} · ${own?.files?.size ?: 0} files · ${ownKind.label}" +
                                    if (!ownHasWorkflow && addWorkflow && Templates.workflowFor(ownKind, repoName) != null) " + build workflow" else ""
                                Contents.EMPTY -> "Empty (README only)"
                            }) { goTo("Contents") }
                            ReviewRow("Repo", "${app.prefs.login}/$repoName · ${if (isPrivate) "private" else "public"}") { goTo("Repo") }
                            if (needsPackage) ReviewRow(if (contents == Contents.ANDROID) "Package" else "App ID", pkg) { goTo("Package") }
                            if (needsKey) ReviewRow("Signing key", keyId?.let { id -> keys.find { it.id == id }?.label } ?: "None (debug key)") { goTo("Signing") }
                        }
                        Panel {
                            Label("When you tap Create")
                            Task.entries.forEachIndexed { i, t ->
                                val skipped = (t == Task.SECRETS && (!needsKey || keyId == null)) || (t == Task.BUILD && contents == Contents.EMPTY)
                                Bullet("${i + 1}", if (skipped) "${t.label} (skipped)" else t.label)
                            }
                        }
                        Text(
                            when (kind) {
                                ProjectKind.ELECTRON -> "Releases use the version in package.json. Bump it to publish a new release."
                                ProjectKind.ANDROID -> "Releases use versionName in app/build.gradle.kts. Bump it to publish a new release."
                                else -> "Releases are tagged vX.Y.Z by the build workflow."
                            },
                            color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
                        )
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
                            if (kind.isAndroid) {
                                Bullet("1", "The first build takes a few minutes. AutoHub notifies you when it's done.")
                                Bullet("2", "Then tap Install on the repo screen.")
                            } else if (kind == ProjectKind.ELECTRON) {
                                Bullet("1", "The Windows build takes a few minutes. AutoHub notifies you when it's done.")
                                Bullet("2", "Then use Release files on the repo screen to send the .exe to your PC, or download it on the PC from the repo's Releases page.")
                            } else Bullet("1", "Open the repo to upload more files or add a workflow.")
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    "Start" -> Button(onClick = { step = 1 }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Start") }
                    "Review" -> {
                        OutlinedButton(onClick = { step -= 1 }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                        Button(onClick = { step = last; runSetup() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Create") }
                    }
                    "Creating" -> {
                        val allDone = Task.entries.all { states[it] == TaskState.DONE || states[it] == TaskState.SKIPPED }
                        when {
                            running -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f).height(50.dp)) { Text("Working…") }
                            allDone -> Button(onClick = { created?.let { onCreated(it.id) } }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Open the repo") }
                            error != null -> {
                                if (created == null) OutlinedButton(onClick = { error = null; states.clear(); goTo("Repo") }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Change details") }
                                Button(onClick = { runSetup() }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Try again") }
                            }
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = { step -= 1 }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Back") }
                        val ok = when (page) {
                            "Name" -> appName.isNotBlank()
                            "Contents" -> contents != Contents.OWN || (own != null && !picking)
                            "Repo" -> validRepo && repoFree != false
                            "Package" -> Templates.validPackage(pkg)
                            else -> true
                        }
                        Button(onClick = { step += 1 }, enabled = ok, modifier = Modifier.weight(1f).height(50.dp)) { Text("Next") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick), accent = if (selected) Stellar.Cyan else Stellar.Line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
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
