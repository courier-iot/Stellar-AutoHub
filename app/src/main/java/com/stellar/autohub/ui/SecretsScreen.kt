package com.stellar.autohub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.secrets.SecretNeed
import com.stellar.autohub.secrets.SecretsManager
import com.stellar.autohub.secrets.SecretsOverview
import com.stellar.autohub.secrets.SigningKey
import com.stellar.autohub.secrets.SigningRole
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

@Composable
fun SecretsScreen(repoId: String, onBack: () -> Unit, openKeys: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = app.store.repo(repoId)
    val keys by app.vault.keys.collectAsState()
    var overview by remember { mutableStateOf<SecretsOverview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) } // secret name, "" = new
    var deleting by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    if (repo == null) { Screen("Secrets", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    LaunchedEffect(refresh) {
        error = null
        overview = try { app.secrets.overview(repo) } catch (e: Exception) { error = e.message; null }
    }

    Screen(
        title = "Secrets · ${repo.name}",
        onBack = onBack,
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") } },
        floating = {
            ExtendedFloatingActionButton(onClick = { editing = "" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New secret") })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Values are encrypted on this phone before upload. GitHub never lets anyone read them back.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            error?.let {
                ErrorBox(it + if (it.contains("403") || it.contains("ermission")) "\nYour token needs Secrets read & write access." else "")
            }
            busy?.let { Busy(it) }
            val ov = overview
            if (ov == null && error == null) Busy("Reading workflows and secrets…")

            Panel(accent = Stellar.Violet) {
                Label("Signing key", Stellar.Violet)
                val signing = ov?.needs?.filter { it.role != SigningRole.NONE }.orEmpty()
                if (signing.isNotEmpty()) {
                    signing.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (n.isSet) "✓" else "!", color = if (n.isSet) Stellar.Green else Stellar.Amber, modifier = Modifier.width(20.dp))
                            MonoText(n.name, modifier = Modifier.weight(1f))
                            Text(n.role.label, color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text("No signing secrets found in the workflows. Applying a key uses the standard names (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { if (keys.isEmpty()) openKeys() else applying = true }, enabled = busy == null) {
                    Text(if (keys.isEmpty()) "Create or import a key first" else "Apply signing key")
                }
            }

            if (ov != null) {
                val other = ov.needs.filter { it.role == SigningRole.NONE }
                Panel {
                    Label("Used by workflows")
                    if (other.isEmpty()) Text("No other secrets referenced.", color = Stellar.Dim)
                    other.forEach { n -> SecretRow(n, onEdit = { editing = n.name }, onDelete = { deleting = n.name }) }
                }
                if (ov.unused.isNotEmpty()) Panel {
                    Label("Set but not used", Stellar.Dim)
                    ov.unused.forEach { n -> SecretRow(n, onEdit = { editing = n.name }, onDelete = { deleting = n.name }) }
                }
            }
            Spacer(Modifier.height(90.dp))
        }
    }

    editing?.let { name ->
        SecretEditDialog(name, onDismiss = { editing = null }) { n, v ->
            editing = null
            busy = "Saving $n…"
            scope.launch {
                try { app.secrets.put(repo, n, v); ctx.toast("$n saved"); refresh++ } catch (e: Exception) { error = e.message } finally { busy = null }
            }
        }
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("Builds that use it will fail until it's set again.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null; busy = "Deleting $name…"
                    scope.launch {
                        try { app.secrets.delete(repo, name); refresh++ } catch (e: Exception) { error = e.message } finally { busy = null }
                    }
                }) { Text("Delete", color = Stellar.Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (applying) {
        ApplyKeyDialog(keys, app.secrets.signingMapping(overview), onDismiss = { applying = false }) { key, mapping ->
            applying = false
            busy = "Applying ${key.label}…"
            scope.launch {
                try {
                    for ((name, role) in mapping) {
                        val v = app.secrets.valueFor(role, key) ?: continue
                        busy = "Setting $name…"
                        app.secrets.put(repo, name, v)
                    }
                    ctx.toast("Signing key applied to ${repo.name}")
                    refresh++
                } catch (e: Exception) { error = e.message } finally { busy = null }
            }
        }
    }
}

@Composable
private fun SecretRow(n: SecretNeed, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (n.isSet) "✓" else "!", color = if (n.isSet) Stellar.Green else Stellar.Amber, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            MonoText(n.name)
            Text(
                when {
                    !n.isSet -> "Missing · used in ${n.usedIn.joinToString()}"
                    n.usedIn.isNotEmpty() -> "Set · used in ${n.usedIn.joinToString()}"
                    else -> "Set"
                },
                color = if (n.isSet) Stellar.Dim else Stellar.Amber, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (n.isSet) TextButton(onClick = onDelete) { Text("Delete", color = Stellar.Red) }
        else TextButton(onClick = onEdit) { Text("Set") }
    }
}

@Composable
private fun SecretEditDialog(name: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }
    var v by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val validName = Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(n) && !n.uppercase().startsWith("GITHUB_")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (name.isEmpty()) "New secret" else "Set $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (name.isEmpty()) OutlinedTextField(n, { n = it.trim().uppercase() }, label = { Text("Name") }, singleLine = true, isError = n.isNotEmpty() && !validName)
                OutlinedTextField(
                    v, { v = it }, label = { Text("Value") }, minLines = 1, maxLines = 6,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(show, { show = it }); Text("Show value")
                }
                if (name.isNotEmpty()) Text("This replaces the current value.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = validName && v.isNotEmpty(), onClick = { onSave(n, v) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ApplyKeyDialog(
    keys: List<SigningKey>,
    initial: Map<String, SigningRole>,
    onDismiss: () -> Unit,
    onApply: (SigningKey, Map<String, SigningRole>) -> Unit,
) {
    var key by remember { mutableStateOf(keys.first()) }
    var mapping by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply signing key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Key")
                keys.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { key = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = key.id == k.id, onClick = { key = k })
                        Column {
                            Text(k.label)
                            Text("alias ${k.alias}${if (!k.verified) " · unverified" else ""}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Label("Secrets to set (tap role to change)")
                mapping.forEach { (name, role) ->
                    var open by remember(name) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoText(name, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { open = true }) { Text(role.label) }
                            DropdownMenu(open, { open = false }) {
                                SigningRole.entries.forEach { r ->
                                    DropdownMenuItem(text = { Text(r.label) }, onClick = {
                                        open = false
                                        mapping = if (r == SigningRole.NONE) mapping - name else mapping + (name to r)
                                    })
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.trim().uppercase() }, label = { Text("Add name") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        mapping = mapping + (newName to SecretsManager.guessRole(newName).let { if (it == SigningRole.NONE) SigningRole.KEYSTORE else it })
                        newName = ""
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(enabled = mapping.isNotEmpty(), onClick = { onApply(key, mapping) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
