package com.stellar.autohub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.install.InstallState
import com.stellar.autohub.install.Installer
import com.stellar.autohub.install.SignatureCheck
import com.stellar.autohub.upload.PathRules
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.launch

/**
 * Shows installed vs released version and offers to install when the app is missing or older.
 * If [runId] is given and there's no release for [expectedVersion], offers the build artifact instead.
 */
@Composable
fun InstallPanel(repo: RepoProfile, runId: Long = 0, expectedVersion: String? = null, refreshKey: Any? = null) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(ctx.applicationContext, app.api) }
    var state by remember { mutableStateOf<InstallState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<Pair<java.io.File, SignatureCheck>?>(null) }

    LaunchedEffect(repo.id, repo.packageName, refreshKey, tick) {
        error = null
        state = try { installer.state(repo) } catch (e: Exception) { error = e.message; null }
    }

    fun go(fromArtifact: Boolean) {
        if (!installer.canInstall()) {
            ctx.toast("Allow AutoHub to install apps, then tap Install again.")
            installer.openInstallPermission()
            return
        }
        scope.launch {
            try {
                progress = 0f
                val st = state
                val apk = if (fromArtifact) installer.downloadArtifactApk(repo, runId) { progress = it }
                else installer.downloadReleaseApk(repo, st!!.latestRelease!!) { progress = it }
                progress = null
                val check = installer.inspect(apk, app.vault.keys.value)
                val wrongPkg = repo.packageName.isNotBlank() && check.apkPackage != null && check.apkPackage != repo.packageName
                if (check.mismatch || wrongPkg || check.apkCert == null) review = apk to check
                else installer.install(apk)
            } catch (e: Exception) {
                progress = null
                error = e.message
            }
        }
    }

    Panel(accent = Stellar.Violet) {
        Label("App on this phone", Stellar.Violet)
        val st = state
        if (repo.packageName.isBlank()) {
            Text("Package name unknown. AutoHub learns it from app/build.gradle(.kts) on your next upload, or set it in repo settings.", color = Stellar.Dim)
            if (runId > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { go(true) }) { Text("Install from this build") }
            }
            return@Panel
        }
        if (st == null && error == null) { Busy("Checking…"); return@Panel }
        error?.let { Text(it, color = Stellar.Red); Spacer(Modifier.height(6.dp)) }
        if (st == null) {
            TextButton(onClick = { tick++ }) { Text("Retry") }
            return@Panel
        }
        val installed = st.installedVersion
        val latest = st.latestTag
        Text(
            buildString {
                append(if (installed == null) "Not installed" else "Installed: v$installed")
                append("   ·   ")
                append(if (latest == null) "No releases yet" else "Latest release: $latest")
            },
        )
        val expectedTag = expectedVersion?.let { "v$it" }
        val releaseMissing = runId > 0 && expectedTag != null && latest != expectedTag
        val newer = latest != null && (installed == null || PathRules.compareVersions(latest, installed) > 0)
        Spacer(Modifier.height(8.dp))
        val p = progress
        when {
            p != null -> {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(p * 100).toInt()}%", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
            }
            releaseMissing -> {
                Text("No $expectedTag release was published, so you can install this build's APK directly.", color = Stellar.Amber)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { go(true) }) { Text("Install from this build") }
            }
            newer && st.apkAsset != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { go(false) }) { Text(if (installed == null) "Install $latest" else "Update to $latest") }
            }
            latest != null && st.apkAsset == null -> Text("Release $latest has no APK attached.", color = Stellar.Amber)
            installed != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Up to date ✓", color = Stellar.Green, modifier = Modifier.padding(top = 10.dp))
                if (st.apkAsset != null) TextButton(onClick = { go(false) }) { Text("Reinstall") }
            }
            runId > 0 -> OutlinedButton(onClick = { go(true) }) { Text("Install from this build") }
        }
    }

    review?.let { (apk, c) ->
        val self = c.apkPackage == ctx.packageName
        AlertDialog(
            onDismissRequest = { review = null },
            title = { Text(if (c.mismatch) "Different signing key" else "Check this APK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        c.apkCert == null -> Text("Android couldn't read this APK's signature. It may be damaged or unsigned.", color = Stellar.Red)
                        c.mismatch -> Text(
                            "The installed app was signed with a different key, so Android won't update it. " +
                                "You'd need to uninstall it first, which deletes its data on this phone." +
                                if (self) "\n\nThis is AutoHub itself: make a backup in Settings first." else "",
                            color = Stellar.Amber,
                        )
                    }
                    if (repo.packageName.isNotBlank() && c.apkPackage != null && c.apkPackage != repo.packageName)
                        Text("This APK is ${c.apkPackage}, but the repo's app is ${repo.packageName}.", color = Stellar.Amber)
                    Text("APK: ${c.apkPackage ?: "?"} v${c.apkVersion ?: "?"}", color = Stellar.Dim)
                    Text("Signed with: ${c.keyLabel ?: "a key not in AutoHub"}", color = Stellar.Dim)
                    c.apkCert?.let { MonoText("New  ${it.take(23)}…", Stellar.Dim) }
                    c.installedCert?.let { MonoText("Installed  ${it.take(23)}…", Stellar.Dim) }
                }
            },
            confirmButton = {
                when {
                    c.mismatch -> TextButton(onClick = { review = null; c.apkPackage?.let(installer::uninstall) }) {
                        Text("Uninstall current", color = Stellar.Red)
                    }
                    c.apkCert != null -> TextButton(onClick = { review = null; scope.launch { installer.install(apk) } }) { Text("Install anyway") }
                }
            },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } },
        )
    }
}
