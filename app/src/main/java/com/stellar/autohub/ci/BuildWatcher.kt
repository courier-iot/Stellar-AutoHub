package com.stellar.autohub.ci

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.MainActivity
import com.stellar.autohub.R
import com.stellar.autohub.data.GhRun
import kotlinx.coroutines.delay

/**
 * Follows the Actions run(s) for one commit until they finish, then posts a result notification
 * and updates the upload history. Runs as a foreground worker so it survives leaving the app.
 */
class BuildWatcher(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val app get() = applicationContext as AutoHubApp
    private val notifId get() = 4000 + (inputData.getString(KEY_HISTORY).hashCode() and 0xfff)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Waiting for the build to start…")

    private fun foreground(text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, AutoHubApp.CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(inputData.getString(KEY_REPO_NAME) ?: "Build")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openIntent(null))
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId, n)
    }

    private suspend fun status(text: String) {
        try { setForeground(foreground(text)) } catch (_: Exception) { /* background start not allowed: keep going quietly */ }
    }

    override suspend fun doWork(): Result {
        val repoId = inputData.getString(KEY_REPO) ?: return Result.failure()
        val sha = inputData.getString(KEY_SHA) ?: return Result.failure()
        val historyId = inputData.getString(KEY_HISTORY) ?: ""
        val repo = app.store.repo(repoId) ?: return Result.failure()
        status("Waiting for the build to start…")

        val started = System.currentTimeMillis()
        var runs: List<GhRun> = emptyList()
        var lastText = ""
        while (System.currentTimeMillis() - started < 75 * 60_000L) {
            runs = try { app.api.runsForSha(repo.owner, repo.name, sha) } catch (e: Exception) { runs }
            if (runs.isEmpty()) {
                if (System.currentTimeMillis() - started > 4 * 60_000L) {
                    app.store.updateHistory(historyId) { it.copy(status = "none") }
                    notifyResult(repo.fullName, "No build started", "GitHub didn't start any workflow for this commit.", repoId, 0, false)
                    return Result.success()
                }
            } else {
                val main = runs.first()
                app.store.updateHistory(historyId) { it.copy(runId = main.id, status = main.status ?: "queued") }
                if (runs.all { it.status == "completed" }) break
                val text = when {
                    runs.any { it.status == "in_progress" } -> "Building… (${(System.currentTimeMillis() - started) / 60000} min)"
                    else -> "Queued on GitHub…"
                }
                if (text != lastText) { status(text); lastText = text }
            }
            delay(if (runs.isEmpty()) 8_000 else 12_000)
        }

        if (runs.isEmpty() || runs.any { it.status != "completed" }) {
            notifyResult(repo.fullName, "Still building", "Stopped watching after 75 minutes.", repoId, runs.firstOrNull()?.id ?: 0, false)
            return Result.success()
        }
        val failed = runs.firstOrNull { it.conclusion !in listOf("success", "skipped", "neutral") }
        val main = failed ?: runs.first()
        val conclusion = failed?.conclusion ?: "success"
        app.store.updateHistory(historyId) { it.copy(runId = main.id, status = if (conclusion == "success") "success" else conclusion ?: "failure") }

        if (failed == null) {
            val version = app.store.data.value.history.find { it.id == historyId }?.version.orEmpty()
            notifyResult(repo.fullName, "Build passed ✓", if (version.isNotEmpty()) "v$version is ready. Tap to install." else "Tap to open.", repoId, main.id, true)
        } else {
            val step = try {
                app.api.jobs(repo.owner, repo.name, failed.id)
                    .firstNotNullOfOrNull { j -> j.steps.firstOrNull { it.conclusion == "failure" }?.let { "${j.name} › ${it.name}" } }
            } catch (e: Exception) { null }
            notifyResult(repo.fullName, "Build failed ✗", step?.let { "Failed at $it. Tap for the error." } ?: "Tap to see the logs.", repoId, failed.id, false)
        }
        return Result.success()
    }

    private fun openIntent(runId: Long?): PendingIntent {
        val i = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_REPO, inputData.getString(KEY_REPO))
            if (runId != null && runId > 0) putExtra(MainActivity.EXTRA_RUN, runId)
        }
        return PendingIntent.getActivity(
            applicationContext, (runId ?: 0L).toInt() xor notifId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyResult(title: String, head: String, body: String, repoId: String, runId: Long, ok: Boolean) {
        if (app.store.repo(repoId)?.muted == true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n: Notification = NotificationCompat.Builder(applicationContext, AutoHubApp.CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$head  ·  $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(if (ok) 0xFF34E7FF.toInt() else 0xFFFF4D6D.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(runId))
            .build()
        NotificationManagerCompat.from(applicationContext).notify(notifId + 1, n)
    }

    companion object {
        const val KEY_REPO = "repo"
        const val KEY_REPO_NAME = "repoName"
        const val KEY_SHA = "sha"
        const val KEY_HISTORY = "history"

        fun start(context: Context, repoId: String, repoName: String, sha: String, historyId: String) {
            val req = OneTimeWorkRequestBuilder<BuildWatcher>()
                .setInputData(workDataOf(KEY_REPO to repoId, KEY_REPO_NAME to repoName, KEY_SHA to sha, KEY_HISTORY to historyId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("watch-$repoId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
