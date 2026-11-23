package com.stellar.autohub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.MainActivity
import com.stellar.autohub.R
import com.stellar.autohub.data.StoreData

/** Home-screen widget: latest build status of up to 4 repos. Tap to open AutoHub. */
class BuildWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val data = try { AutoHubApp.instance.store.data.value } catch (e: Exception) { StoreData() }
        val views = render(context, data)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        private val rows = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4)

        private fun color(status: String?) = when (status) {
            "success" -> 0xFF3DFFA8.toInt()
            "failure", "timed_out", "startup_failure" -> 0xFFFF5C7A.toInt()
            "in_progress" -> 0xFF34E7FF.toInt()
            "queued", "waiting", "pending" -> 0xFFFFC14D.toInt()
            else -> 0xFFA3ACD6.toInt()
        }

        private fun label(status: String?) = when (status) {
            "success" -> "✓ passed"
            "failure" -> "✗ failed"
            "in_progress" -> "● building"
            "queued", "waiting", "pending" -> "○ queued"
            "cancelled" -> "– cancelled"
            null, "none" -> "–"
            else -> status
        }

        private fun ago(t: Long): String {
            val s = (System.currentTimeMillis() - t) / 1000
            return when { s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d" }
        }

        fun render(context: Context, data: StoreData): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_builds)
            // Most recently active repos first
            val latest = data.repos.map { r -> r to data.history.filter { it.repoId == r.id }.maxByOrNull { it.time } }
                .sortedByDescending { it.second?.time ?: 0 }
            rows.forEachIndexed { i, id ->
                val item = latest.getOrNull(i)
                if (item == null) {
                    v.setViewVisibility(id, if (i == 0) View.VISIBLE else View.GONE)
                    if (i == 0) { v.setTextViewText(id, "No repos yet"); v.setTextColor(id, 0xFFA3ACD6.toInt()) }
                } else {
                    val (repo, h) = item
                    v.setViewVisibility(id, View.VISIBLE)
                    v.setTextViewText(id, "${repo.name}  ${label(h?.status)}" + (h?.let { " · ${ago(it.time)}" } ?: ""))
                    v.setTextColor(id, color(h?.status))
                }
            }
            val pi = PendingIntent.getActivity(
                context, 9001, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }

        /** Re-draws every placed widget (called whenever upload history changes). */
        fun refresh(context: Context, data: StoreData) {
            try {
                val m = AppWidgetManager.getInstance(context)
                val ids = m.getAppWidgetIds(ComponentName(context, BuildWidget::class.java))
                if (ids.isNotEmpty()) {
                    val views = render(context, data)
                    ids.forEach { m.updateAppWidget(it, views) }
                }
            } catch (_: Exception) { }
        }
    }
}
