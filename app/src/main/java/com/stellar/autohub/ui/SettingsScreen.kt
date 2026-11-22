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
import com.stellar.autohub.BuildConfig
import com.stellar.autohub.data.Prefs
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.ui.theme.Stellar

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val app = AutoHubApp.instance
    var watch by remember { mutableStateOf(app.prefs.watchBuilds) }
    var strip by remember { mutableStateOf(app.prefs.autoStrip) }
    var ignore by remember { mutableStateOf(app.prefs.ignorePatterns) }
    var clientId by remember { mutableStateOf(app.prefs.clientId) }
    var confirmOut by remember { mutableStateOf(false) }

    Screen(title = "Settings", onBack = {
        app.prefs.ignorePatterns = ignore
        app.prefs.clientId = clientId
        onBack()
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel {
                Label("Account")
                Text("Signed in as ${app.prefs.login.ifBlank { "?" }}")
                app.api.lastScopes?.takeIf { it.isNotBlank() }?.let {
                    Text("Token scopes: $it", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    if (!it.contains("workflow")) Text("Missing 'workflow' scope: uploads that change .github/workflows will be rejected.", color = Stellar.Amber, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("OAuth Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { confirmOut = true }) { Text("Sign out", color = Stellar.Red) }
            }

            Panel {
                Label("Uploads")
                Toggle("Watch builds & notify", "Follows each build in the background and pings you when it passes or fails.", watch) {
                    watch = it; app.prefs.watchBuilds = it
                }
                Toggle("Auto-strip top folder", "If a zip has everything inside one folder (MyApp/…), upload its contents instead.", strip) {
                    strip = it; app.prefs.autoStrip = it
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    ignore, { ignore = it }, label = { Text("Never upload (one pattern per line)") },
                    minLines = 5, modifier = Modifier.fillMaxWidth(),
                )
                Text("These are skipped from uploads and never deleted from repos. Keystores are on the list so your signing key never lands in a repo.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { ignore = Prefs.DEFAULT_IGNORE }) { Text("Reset to defaults") }
            }

            Panel {
                Label("About")
                Text("Stellar AutoHub v${BuildConfig.VERSION_NAME}")
                Text("Add this app's own repo to AutoHub and it can update itself too.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false },
        title = { Text("Sign out?") },
        text = { Text("Your saved repos, history and signing keys stay on this phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmOut = false
                app.setToken(null)
                app.prefs.login = ""
                onSignedOut()
            }) { Text("Sign out", color = Stellar.Red) }
        },
        dismissButton = { TextButton(onClick = { confirmOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}

/** Shown when a zip/files are shared into AutoHub from another app. */
@Composable
fun ShareTargetScreen(vm: AppViewModel, onBack: () -> Unit, onPicked: (String) -> Unit, addRepo: () -> Unit) {
    val app = AutoHubApp.instance
    val shared by vm.sharedUris.collectAsState()
    val data by app.store.data.collectAsState()
    var guess by remember { mutableStateOf<RepoProfile?>(null) }
    var reading by remember { mutableStateOf(true) }

    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) { reading = true; guess = vm.guessRepo(shared); reading = false }
    }

    Screen(title = "Upload to…", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${shared.size} item(s) shared to AutoHub", color = Stellar.Dim)
            if (reading) Busy("Reading…")
            guess?.let { g ->
                Panel(Modifier.clickable { onPicked(g.id) }, accent = Stellar.Cyan) {
                    Label("Looks like")
                    Text(g.fullName, style = MaterialTheme.typography.titleMedium)
                    Text("Tap to preview", color = Stellar.Dim)
                }
            }
            if (data.repos.isEmpty()) {
                Panel { Text("Add a repo first.") }
                Button(onClick = addRepo) { Text("Add repo") }
            }
            data.repos.filter { it.id != guess?.id }.forEach { r ->
                Panel(Modifier.clickable(enabled = !reading) {
                    if (vm.payload.value == null) vm.loadUris(shared, "", onReady = { onPicked(r.id) }, onError = {})
                    else onPicked(r.id)
                }) {
                    Text(r.fullName)
                }
            }
        }
    }
}
