package com.stellar.autohub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.data.GhRun
import com.stellar.autohub.ui.theme.Stellar

private fun durationSec(r: GhRun): Long? = try {
    val a = java.time.Instant.parse(r.run_started_at ?: r.created_at ?: return null)
    val b = java.time.Instant.parse(r.updated_at ?: return null)
    (b.epochSecond - a.epochSecond).takeIf { it >= 0 }
} catch (e: Exception) { null }

fun fmtDur(s: Long) = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

@Composable
fun StatsScreen(repoId: String, onBack: () -> Unit) {
    val app = AutoHubApp.instance
    val repo = app.store.repo(repoId)
    var runs by remember { mutableStateOf<List<GhRun>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (repo == null) { Screen("Stats", onBack) { Text("Repo not found", Modifier.padding(it).padding(16.dp)) }; return }

    LaunchedEffect(Unit) {
        try { runs = app.api.allRuns(repo.owner, repo.name, max = 300).filter { it.status == "completed" } }
        catch (e: Exception) { error = e.message }
    }

    Screen(title = "Build stats · ${repo.name}", onBack = onBack) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { ErrorBox(it) }
            val list = runs
            if (list == null && error == null) Busy("Loading build history…")
            if (list != null && list.isEmpty()) Panel { Text("No finished builds yet.", color = Stellar.Dim) }
            if (list != null && list.isNotEmpty()) {
                val recent = list.sortedByDescending { it.created_at }.take(30)
                val passed = recent.count { it.conclusion == "success" }
                val durs = recent.mapNotNull { r -> durationSec(r)?.let { r to it } }
                val okDurs = durs.filter { it.first.conclusion == "success" }.map { it.second }.sorted()
                val streak = recent.takeWhile { it.conclusion == "success" }.size
                Panel(accent = Stellar.Cyan) {
                    Label("Last ${recent.size} builds")
                    Text("Pass rate: ${passed * 100 / recent.size}%  ($passed passed, ${recent.size - passed} failed/cancelled)")
                    if (okDurs.isNotEmpty()) {
                        Text("Typical build: ${fmtDur(okDurs[okDurs.size / 2])}  ·  fastest ${fmtDur(okDurs.first())}  ·  slowest ${fmtDur(okDurs.last())}")
                    }
                    Text(if (streak > 0) "Current streak: $streak passing in a row 🔥" else "Last build didn't pass", color = Stellar.Dim)
                }
                Panel {
                    Label("Build time (oldest → newest)")
                    val bars = durs.reversed()
                    val max = (bars.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                    val green = Stellar.Green; val red = Stellar.Red; val dim = Stellar.Dim
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        if (bars.isEmpty()) return@Canvas
                        val w = size.width / bars.size
                        bars.forEachIndexed { i, (r, d) ->
                            val h = size.height * d / max
                            val c = when (r.conclusion) { "success" -> green; "failure" -> red; else -> dim }
                            drawRect(c, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
                        }
                    }
                    Text("Tallest bar: ${fmtDur(max)}. Green = passed, red = failed.", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                    if (okDurs.size >= 6) {
                        val half = okDurs.size / 2
                        val firstHalf = durs.reversed().filter { it.first.conclusion == "success" }.take(half).map { it.second }.average()
                        val lastHalf = durs.reversed().filter { it.first.conclusion == "success" }.drop(half).map { it.second }.average()
                        if (lastHalf > firstHalf * 1.25) Text("Builds are getting slower (${fmtDur(firstHalf.toLong())} → ${fmtDur(lastHalf.toLong())}).", color = Stellar.Amber)
                    }
                }
                Panel {
                    Label("By workflow")
                    list.groupBy { it.name ?: "?" }.forEach { (name, rs) ->
                        val ok = rs.count { it.conclusion == "success" }
                        Text("$name: ${rs.size} runs, ${ok * 100 / rs.size}% passed")
                    }
                    Text("${list.size} finished runs in total (up to 300 counted).", color = Stellar.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
