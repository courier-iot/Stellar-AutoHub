package com.stellar.autohub.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.BuildConfig
import com.stellar.autohub.security.Backup
import com.stellar.autohub.security.BackupData
import com.stellar.autohub.security.Guard
import com.stellar.autohub.security.SecureScreen
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Full-screen cover shown while the app is locked. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    SecureScreen()
    BackHandler { (ctx as? Activity)?.moveTaskToBack(true) }
    fun unlock() {
        if (!Guard.available(ctx)) { onUnlocked(); return }
        Guard.prompt(ctx, "Unlock Stellar AutoHub", onOk = onUnlocked)
    }
    LaunchedEffect(Unit) { unlock() }
    Surface(Modifier.fillMaxSize(), color = Stellar.Space) {
        Box(Modifier.fillMaxSize()) {
            Starfield()
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔒", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Stellar AutoHub is locked", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { unlock() }) { Text("Unlock") }
            }
        }
    }
}

@Composable
fun CrashScreen(onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(app.crashes()) }
    var open by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) { app.prefs.lastCrashSeen = System.currentTimeMillis() }

    Screen(title = "Crash logs", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Saved only on this phone. Nothing is sent anywhere.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            if (files.isEmpty()) Panel { Text("No crashes recorded ✓", color = Stellar.Green) }
            files.forEach { f ->
                val text = remember(f) { f.readText() }
                val firstError = remember(text) { text.lines().firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "Crash" }
                Panel(accent = Stellar.Red.copy(alpha = 0.6f)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(app.crashTime(f))), color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    MonoText(firstError.take(200), Stellar.Red)
                    Row {
                        Button(onClick = { ctx.copy("Crash report", text) }) { Text("Copy for Claude") }
                        TextButton(onClick = { open = if (open == f) null else f }) { Text(if (open == f) "Hide" else "Show") }
                        TextButton(onClick = { f.delete(); files = app.crashes() }) { Text("Delete", color = Stellar.Red) }
                    }
                    if (open == f) MonoText(text, Stellar.Text.copy(alpha = 0.85f))
                }
            }
            if (files.size > 1) TextButton(onClick = { files.forEach { it.delete() }; files = app.crashes() }) { Text("Delete all", color = Stellar.Red) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by app.vault.keys.collectAsState()
    val data by app.store.data.collectAsState()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var restorePw by remember { mutableStateOf("") }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    SecureScreen()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                app.prefs.lastBackup = System.currentTimeMillis()
                result = "Backup saved ✓ Keep the file and its password somewhere safe (e.g. cloud drive + password manager)."
                pw = ""; pw2 = ""
            } catch (e: Exception) { result = e.message }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreBytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
    }

    Screen(title = "Backup & restore", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result?.let { InfoBox(it, Stellar.Cyan) }
            busy?.let { Busy(it) }

            Panel(accent = Stellar.Violet) {
                Label("Make a backup", Stellar.Violet)
                Text("${data.repos.size} repo(s), ${data.history.size} history entries, ${keys.size} signing key(s) and your settings. " +
                    "Your GitHub sign-in is not included.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                if (app.prefs.lastBackup > 0) Text("Last backup: ${ago(app.prefs.lastBackup)}", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text("Backup password (8+ chars)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = pw2.isNotEmpty() && pw2 != pw, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(enabled = busy == null && pw.length >= 8 && pw == pw2, onClick = {
                    Guard.sensitive(ctx, "Export signing keys") {
                        busy = "Encrypting…"
                        scope.launch {
                            try {
                                val bd = BackupData(
                                    created = System.currentTimeMillis(), app = BuildConfig.VERSION_NAME,
                                    store = app.store.data.value, keys = app.vault.keys.value, settings = app.prefs.exportMap(),
                                )
                                pendingExport = withContext(Dispatchers.Default) { Backup.encrypt(bd, pw) }
                                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
                                save.launch("stellar-autohub-backup-$day.sahb")
                            } catch (e: Exception) { result = e.message } finally { busy = null }
                        }
                    }
                }) { Text("Save backup file") }
                Text("Without this password the file can't be opened, not even by AutoHub.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }

            Panel {
                Label("Restore")
                Text("Adds repos, history and keys from a backup. Nothing you have now is removed.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (restoreBytes == null) "Pick backup file" else "File picked ✓ (pick another)") }
                if (restoreBytes != null) {
                    OutlinedTextField(restorePw, { restorePw = it }, label = { Text("Backup password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(enabled = busy == null && restorePw.isNotEmpty(), onClick = {
                        busy = "Decrypting…"
                        scope.launch {
                            try {
                                val bd = withContext(Dispatchers.Default) { Backup.decrypt(restoreBytes!!, restorePw) }
                                val repos = app.store.restore(bd.store)
                                val k = app.vault.restore(bd.keys)
                                app.prefs.importMap(bd.settings)
                                result = "Restored ✓ $repos new repo(s), $k new key(s) (backup from ${DateFormat.getDateInstance().format(Date(bd.created))})."
                                restoreBytes = null; restorePw = ""
                            } catch (e: Exception) { result = e.message ?: "Couldn't restore" } finally { busy = null }
                        }
                    }) { Text("Restore") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
