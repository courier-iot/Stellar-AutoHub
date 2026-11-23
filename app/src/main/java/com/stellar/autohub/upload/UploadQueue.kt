package com.stellar.autohub.upload

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.MainActivity
import com.stellar.autohub.R
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.QueuedUpload
import com.stellar.autohub.github.GitHubException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uploads saved on the phone until there's a connection. Each one is a folder: meta.json + payload.zip. */
object UploadQueue {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _items = MutableStateFlow<List<QueuedUpload>>(emptyList())
    val items: StateFlow<List<QueuedUpload>> = _items

    private fun root(ctx: Context) = File(ctx.filesDir, "queue").apply { mkdirs() }

    fun refresh(ctx: Context) {
        _items.value = root(ctx).listFiles()?.mapNotNull { d ->
            try { json.decodeFromString(QueuedUpload.serializer(), File(d, "meta.json").readText()) } catch (e: Exception) { null }
        }?.sortedBy { it.created } ?: emptyList()
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun add(ctx: Context, payload: Payload, meta: QueuedUpload): QueuedUpload {
        val m = meta.copy(id = meta.id.ifEmpty { UUID.randomUUID().toString() })
        val dir = File(root(ctx), m.id).apply { mkdirs() }
        ZipOutputStream(File(dir, "payload.zip").outputStream().buffered()).use { z ->
            for (f in payload.files.associateBy { it.path }.values) {
                z.putNextEntry(ZipEntry(f.path)); z.write(f.bytes); z.closeEntry()
            }
        }
        save(ctx, m)
        schedule(ctx)
        return m
    }

    fun save(ctx: Context, m: QueuedUpload) {
        val dir = File(root(ctx), m.id).apply { mkdirs() }
        File(dir, "meta.json").writeText(json.encodeToString(QueuedUpload.serializer(), m))
        refresh(ctx)
    }

    fun remove(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
        refresh(ctx)
    }

    fun payload(ctx: Context, m: QueuedUpload): Payload {
        val bytes = File(File(root(ctx), m.id), "payload.zip").readBytes()
        return Payload(PayloadReader.readZip(bytes), m.label, m.structured)
    }

    fun schedule(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<QueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofMinutes(1))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/** Sends queued uploads once online. Never commits something with an unreviewed secret warning. */
class QueueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app get() = applicationContext as AutoHubApp

    override suspend fun doWork(): Result {
        UploadQueue.refresh(app)
        var retry = false
        for (m in UploadQueue.items.value.filter { it.status == "waiting" }) {
            val repo = app.store.repo(m.repoId) ?: run { UploadQueue.remove(app, m.id); null } ?: continue
            try {
                val payload = UploadQueue.payload(app, m)
                val remote = app.committer.loadRemote(repo)
                val plan = Planner.plan(
                    payload, remote,
                    ignore = PathRules.parsePatterns(app.prefs.ignorePatterns),
                    protectedPatterns = PathRules.parsePatterns(repo.protectedPatterns),
                    strip = m.stripOverride, autoStrip = app.prefs.autoStrip,
                ).excluding(m.excluded.toSet())

                val unreviewed = if (app.prefs.scanSecrets) plan.leaks.filter { it.key() !in m.allowedLeaks } else emptyList()
                if (unreviewed.isNotEmpty()) {
                    UploadQueue.save(app, m.copy(status = "needs_review", error = "Possible secret in ${unreviewed.first().path}"))
                    notify(m, "Upload needs a look", "Possible ${unreviewed.first().kind.lowercase()} in ${unreviewed.first().path}. Open AutoHub to review it.")
                    continue
                }
                val deletes = if (m.reviewed && m.mode == "MIRROR")
                    m.deletes.toSet().intersect(plan.deletable.map { it.path }.toSet()) else emptySet()
                if (plan.added + plan.changed + deletes.size == 0) {
                    UploadQueue.remove(app, m.id)
                    notify(m, "Queued upload skipped", "Nothing changed compared to the repo anymore.")
                    continue
                }
                val sha = app.committer.commit(repo, plan, deletes, m.message) {}
                val entry = HistoryEntry(
                    id = UUID.randomUUID().toString(), repoId = repo.id, time = System.currentTimeMillis(),
                    message = m.message, commitSha = sha, version = plan.version ?: "",
                    added = plan.added, changed = plan.changed, deleted = deletes.size,
                    status = if (app.prefs.watchBuilds) "queued" else "none",
                )
                app.store.addHistory(entry)
                UploadQueue.remove(app, m.id)
                if (app.prefs.watchBuilds) BuildWatcher.start(app, repo.id, repo.fullName, sha, entry.id)
                notify(m, "Queued upload sent ✓", "${repo.fullName}: ${m.message.lineSequence().first()}")
            } catch (e: GitHubException) {
                UploadQueue.save(app, m.copy(status = "failed", error = e.message))
                notify(m, "Queued upload failed", e.message ?: "GitHub refused it")
            } catch (e: Exception) {
                // Most likely the connection dropped again: try later
                retry = true
                UploadQueue.save(app, m.copy(error = e.message))
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private fun notify(m: QueuedUpload, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_REPO, m.repoId)
        }
        val pi = PendingIntent.getActivity(app, m.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(app).notify(
            7000 + (m.id.hashCode() and 0xfff),
            NotificationCompat.Builder(app, AutoHubApp.CH_RESULT)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).setContentIntent(pi).build()
        )
    }
}
