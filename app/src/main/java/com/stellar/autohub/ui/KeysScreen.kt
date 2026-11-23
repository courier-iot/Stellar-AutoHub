package com.stellar.autohub.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.secrets.KeyImportException
import com.stellar.autohub.secrets.SigningKey
import com.stellar.autohub.security.Guard
import com.stellar.autohub.security.SecureScreen
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun KeysScreen(onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by app.vault.keys.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var exportKey by remember { mutableStateOf<SigningKey?>(null) }
    var deleteKey by remember { mutableStateOf<SigningKey?>(null) }
    var reveal by remember { mutableStateOf<SigningKey?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val k = exportKey
        exportKey = null
        if (uri != null && k != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(app.vault.keystoreBytes(k)) }
                ctx.toast("Saved ${k.label}. Keep it and its password somewhere safe.")
            } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't save") }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Couldn't read file")
                importBytes = com.stellar.autohub.upload.PayloadReader.displayName(ctx, uri) to bytes
            } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't read file") }
        }
    }

    fun export(k: SigningKey) {
        exportKey = k
        saveFile.launch("${k.label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jks")
    }
    SecureScreen()

    Screen(title = "Signing keys", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Android only installs an update if it's signed with the same key as the installed app. Make one key and reuse it everywhere, and keep a backup of the file and password.",
                color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("Create key") }
                OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("Import key file") }
            }
            busy?.let { Busy(it) }
            if (keys.isEmpty()) Panel { Text("No keys yet.", color = Stellar.Dim) }
            keys.forEach { k ->
                Panel(accent = Stellar.Violet) {
                    Text(k.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "alias ${k.alias} · ${DateFormat.getDateInstance().format(Date(k.created))}${if (!k.verified) " · unverified" else ""}",
                        color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
                    )
                    if (k.fingerprint.isNotEmpty()) MonoText("SHA-256 ${k.fingerprint}…", Stellar.Dim)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { Guard.sensitive(ctx, "Save ${k.label}") { export(k) } }) { Text("Save file") }
                        TextButton(onClick = { Guard.sensitive(ctx, "Show key password") { reveal = k } }) { Text("Show password") }
                        TextButton(onClick = { deleteKey = k }) { Text("Delete", color = Stellar.Red) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (creating) CreateKeyDialog(onDismiss = { creating = false }) { label, alias, pw, name, org ->
        creating = false
        busy = "Generating key…"
        scope.launch {
            try {
                val k = withContext(Dispatchers.Default) { app.vault.create(label, alias, pw, name, org) }
                ctx.toast("Key created. Now pick where to save the .jks file.")
                export(k)
            } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't create key") } finally { busy = null }
        }
    }

    importBytes?.let { (fileName, bytes) ->
        ImportKeyDialog(fileName, onDismiss = { importBytes = null }) { label, alias, sp, kp, force, onFail ->
            try {
                app.vault.import(label, bytes, alias, sp, kp, force)
                importBytes = null
                ctx.toast("Imported $label")
            } catch (e: KeyImportException) {
                onFail(e.message ?: "Couldn't open it", true)
            } catch (e: Exception) {
                onFail(e.message ?: "Couldn't open it", false)
            }
        }
    }

    reveal?.let { k ->
        AlertDialog(
            onDismissRequest = { reveal = null },
            title = { Text(k.label) },
            text = {
                Column {
                    Text("Alias: ${k.alias}")
                    MonoText("Store password: ${k.password}")
                    if (k.keyPassword.isNotEmpty()) MonoText("Key password: ${k.keyPassword}")
                }
            },
            confirmButton = { TextButton(onClick = { ctx.copy("Key password", k.password); reveal = null }) { Text("Copy password") } },
            dismissButton = { TextButton(onClick = { reveal = null }) { Text("Close") } },
        )
    }

    deleteKey?.let { k ->
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("Delete ${k.label}?") },
            text = { Text("Only removes it from AutoHub. If you have no other copy of the file, apps signed with it can't be updated anymore, only uninstalled and reinstalled.") },
            confirmButton = { TextButton(onClick = { deleteKey = null; Guard.sensitive(ctx, "Delete ${k.label}") { app.vault.remove(k.id) } }) { Text("Delete", color = Stellar.Red) } },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf("Stellar release key") }
    var alias by remember { mutableStateOf("stellar") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("Stellar") }
    val ok = pw.length >= 6 && pw == pw2 && alias.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create signing key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label (just for you)") }, singleLine = true)
                OutlinedTextField(alias, { alias = it.trim() }, label = { Text("Key alias") }, singleLine = true)
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (6+ chars)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = pw2.isNotEmpty() && pw2 != pw)
                OutlinedTextField(name, { name = it }, label = { Text("Your name (in the certificate)") }, singleLine = true)
                OutlinedTextField(org, { org = it }, label = { Text("Organization (optional)") }, singleLine = true)
                Text("RSA 2048 · valid 30 years · PKCS12. The same password works as store and key password.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onCreate(label, alias, pw, name, org) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onImport: (label: String, alias: String, storePw: String, keyPw: String, force: Boolean, onFail: (String, Boolean) -> Unit) -> Unit,
) {
    var label by remember { mutableStateOf(fileName.substringBeforeLast('.')) }
    var alias by remember { mutableStateOf("") }
    var sp by remember { mutableStateOf("") }
    var kp by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var canForce by remember { mutableStateOf(false) }
    val fail: (String, Boolean) -> Unit = { m, f -> err = m; canForce = f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import $fileName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                OutlinedTextField(alias, { alias = it.trim() }, label = { Text("Key alias (blank = auto)") }, singleLine = true)
                OutlinedTextField(sp, { sp = it }, label = { Text("Store password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(kp, { kp = it }, label = { Text("Key password (blank = same)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                err?.let { Text(it, color = Stellar.Red) }
            }
        },
        confirmButton = {
            Row {
                if (canForce) TextButton(enabled = alias.isNotBlank(), onClick = { onImport(label, alias, sp, kp, true, fail) }) { Text("Save anyway") }
                TextButton(enabled = sp.isNotEmpty(), onClick = { onImport(label, alias, sp, kp, false, fail) }) { Text("Import") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
