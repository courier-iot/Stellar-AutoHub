package com.stellar.autohub.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.stellar.autohub.data.GhRelease
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** What's on the phone vs what GitHub has, for one repo's app. */
data class InstallState(
    val packageName: String,
    val installedVersion: String?,
    val latestRelease: GhRelease?,
) {
    val latestTag get() = latestRelease?.tag_name
    val apkAsset get() = latestRelease?.assets?.firstOrNull { it.name.endsWith(".apk", true) }
}

class Installer(private val context: Context, private val api: GitHubApi) {

    fun installedVersion(pkg: String): String? = try {
        if (pkg.isBlank()) null else {
            val info = if (Build.VERSION.SDK_INT >= 33)
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0)
            info.versionName ?: "?"
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    suspend fun state(repo: RepoProfile): InstallState =
        InstallState(repo.packageName, installedVersion(repo.packageName), api.latestRelease(repo.owner, repo.name))

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun apkDir() = File(context.cacheDir, "apk").apply { mkdirs() }

    suspend fun downloadReleaseApk(repo: RepoProfile, release: GhRelease, progress: (Float) -> Unit): File {
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", true) }
            ?: throw IOException("Release ${release.tag_name} has no APK attached.")
        apkDir().listFiles()?.forEach { it.delete() }
        val dest = File(apkDir(), asset.name.replace('/', '_'))
        api.download(asset.url, "application/octet-stream", dest) { done, total ->
            progress(if (total > 0) done.toFloat() / total else (if (asset.size > 0) done.toFloat() / asset.size else 0f))
        }
        return dest
    }

    /** For builds that finished without publishing a release: pulls the APK out of the run's artifact zip. */
    suspend fun downloadArtifactApk(repo: RepoProfile, runId: Long, progress: (Float) -> Unit): File {
        val arts = api.artifacts(repo.owner, repo.name, runId).filter { !it.expired }
        if (arts.isEmpty()) throw IOException("This build didn't upload any artifacts (or they expired).")
        val art = arts.firstOrNull { it.name.contains("apk", true) } ?: arts.first()
        apkDir().listFiles()?.forEach { it.delete() }
        val zip = File(apkDir(), "artifact.zip")
        api.download(art.archive_download_url, "application/vnd.github+json", zip) { done, _ ->
            progress(if (art.size_in_bytes > 0) (done.toFloat() / art.size_in_bytes).coerceAtMost(1f) else 0f)
        }
        return withContext(Dispatchers.IO) {
            var out: File? = null
            ZipInputStream(zip.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory && e.name.endsWith(".apk", true)) {
                        val f = File(apkDir(), e.name.substringAfterLast('/'))
                        f.outputStream().use { zin.copyTo(it) }
                        out = f
                        break
                    }
                }
            }
            zip.delete()
            out ?: throw IOException("No APK inside the artifact \"${art.name}\".")
        }
    }

    /** Hands the APK to Android's installer (system confirmation screen appears). */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Installed ✓", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Toast.makeText(
                context,
                "Install blocked: the installed app was signed with a different key. Uninstall it first, then install again.",
                Toast.LENGTH_LONG
            ).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Install failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
