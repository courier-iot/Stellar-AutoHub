package com.stellar.autohub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.editor.Templates
import com.stellar.autohub.upload.Payload
import com.stellar.autohub.upload.PathRules
import com.stellar.autohub.upload.Planner
import com.stellar.autohub.upload.RemoteState
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun NewProjectScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val app = AutoHubApp.instance
    val scope = rememberCoroutineScope()
    val keys by app.vault.keys.collectAsState()
    var appName by remember { mutableStateOf("") }
    var repoName by remember { mutableStateOf("") }
    var repoEdited by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf("") }
    var pkgEdited by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(true) }
    var keyId by remember { mutableStateOf(keys.firstOrNull()?.id) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val validRepo = Regex("^[A-Za-z0-9._-]{1,100}$").matches(repoName)
    val ok = appName.isNotBlank() && validRepo && Templates.validPackage(pkg) && busy == null

    Screen(title = "New project", onBack = if (busy == null) onBack else null) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Creates a GitHub repo with a starter Compose app, the Stellar look, and the build + release workflow. The first build starts right away.",
                color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            error?.let { ErrorBox(it) }
            Panel {
                OutlinedTextField(appName, {
                    appName = it
                    if (!repoEdited) repoName = Templates.repoNameFor("Stellar " + it).let { n -> if (it.startsWith("Stellar", true)) Templates.repoNameFor(it) else n }
                    if (!pkgEdited) pkg = Templates.packageFor(it.removePrefix("Stellar ").removePrefix("stellar "))
                }, label = { Text("App name, e.g. Stellar Notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repoName, { repoName = it.trim(); repoEdited = true }, label = { Text("Repo name") }, singleLine = true,
                    isError = repoName.isNotEmpty() && !validRepo, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pkg, { pkg = it.trim().lowercase(); pkgEdited = true }, label = { Text("Package (applicationId)") }, singleLine = true,
                    isError = pkg.isNotEmpty() && !Templates.validPackage(pkg), modifier = Modifier.fillMaxWidth())
                Text("The package can't change later without losing installs, so pick it carefully.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(isPrivate, { isPrivate = it }); Spacer(Modifier.width(8.dp)); Text(if (isPrivate) "Private repo" else "Public repo")
                }
            }
            Panel(accent = Stellar.Violet) {
                Label("Signing key", Stellar.Violet)
                if (keys.isEmpty()) Text("No keys in AutoHub yet. The first build uses a temporary debug key. You can apply a key later in Secrets.", color = Stellar.Dim)
                keys.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { keyId = k.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(keyId == k.id, { keyId = k.id }); Text(k.label)
                    }
                }
                if (keys.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { keyId = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(keyId == null, { keyId = null }); Text("None for now", color = Stellar.Dim)
                }
            }
            busy?.let { Panel(accent = Stellar.Cyan) { Busy(it) } }
            Button(enabled = ok, modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
                error = null
                scope.launch {
                    try {
                        busy = "Creating $repoName on GitHub…"
                        val r = app.api.createRepo(repoName, isPrivate, "$appName, made with Stellar AutoHub")
                        val profile = RepoProfile(
                            id = UUID.randomUUID().toString(), owner = r.owner.login, name = r.name,
                            branch = "main", defaultBranch = "main", packageName = pkg,
                        )
                        app.store.upsertRepo(profile)
                        // Secrets first, so the very first build is already signed
                        keyId?.let { app.vault.get(it) }?.let { k ->
                            busy = "Adding signing secrets…"
                            val m = app.secrets.signingMapping(null)
                            for ((name, role) in m) app.secrets.valueFor(role, k)?.let { v -> app.secrets.put(profile, name, v) }
                        }
                        busy = "Uploading the starter project…"
                        val payload = Payload(Templates.files(appName.trim(), pkg, r.name), "Starter project", structured = true)
                        val plan = Planner.plan(payload, RemoteState(null, null, emptyList(), false),
                            PathRules.parsePatterns(app.prefs.ignorePatterns), emptyList(), false, false)
                        val sha = app.committer.commit(profile, plan, emptySet(), "Start $appName with Stellar AutoHub") { busy = it }
                        val entry = HistoryEntry(UUID.randomUUID().toString(), profile.id, System.currentTimeMillis(),
                            "Start $appName", sha, "1.0.0", plan.added, 0, 0, status = "queued")
                        app.store.addHistory(entry)
                        BuildWatcher.start(app, profile.id, profile.fullName, sha, entry.id)
                        busy = null
                        onCreated(profile.id)
                    } catch (e: Exception) {
                        busy = null
                        error = (e.message ?: "Couldn't create the project") +
                            if (e.message?.contains("name already exists") == true) "\nPick another repo name." else ""
                    }
                }
            }) { Text("Create project") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

