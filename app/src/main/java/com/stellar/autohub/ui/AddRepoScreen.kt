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
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.data.GhRepo
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.upload.PathRules
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AddRepoScreen(onBack: () -> Unit, onAdded: (String) -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<GhRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try { repos = app.api.myRepos() } catch (e: Exception) { error = e.message }
    }

    fun add(owner: String, name: String) {
        adding = "$owner/$name"
        scope.launch {
            try {
                val r = app.api.repo(owner, name)
                val existing = app.store.data.value.repos.find { it.fullName.equals(r.full_name, true) }
                if (existing != null) { onAdded(existing.id); return@launch }
                val pkg = PathRules.GRADLE_FILES.firstNotNullOfOrNull { path ->
                    try { app.api.rawFile(r.owner.login, r.name, path, r.default_branch)?.let { PathRules.applicationId(it) } } catch (e: Exception) { null }
                } ?: ""
                val profile = RepoProfile(
                    id = UUID.randomUUID().toString(), owner = r.owner.login, name = r.name,
                    branch = r.default_branch, packageName = pkg,
                )
                app.store.upsertRepo(profile)
                onAdded(profile.id)
            } catch (e: Exception) {
                ctx.toast(e.message ?: "Couldn't add that repo")
            } finally { adding = null }
        }
    }

    Screen(title = "Add repo", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.trim() },
                label = { Text("Search, or type owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (query.contains('/') && query.split('/').size == 2 && query.split('/').all { it.isNotBlank() }) {
                TextButton(onClick = { val (o, n) = query.split('/'); add(o, n) }) { Text("Add $query") }
            }
            adding?.let { Busy("Adding $it…") }
            error?.let { ErrorBox(it) }
            Spacer(Modifier.height(8.dp))
            val list = repos
            if (list == null && error == null) Busy("Loading your repos…")
            if (list != null) {
                val filtered = list.filter { query.isBlank() || it.full_name.contains(query, true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.full_name }) { r ->
                        Panel(Modifier.clickable(enabled = adding == null) { add(r.owner.login, r.name) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${r.owner.login} · ${r.default_branch}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                                }
                                if (r.private) Chip("PRIVATE", Stellar.Violet)
                            }
                        }
                    }
                }
            }
        }
    }
}
