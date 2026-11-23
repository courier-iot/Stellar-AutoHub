package com.stellar.autohub.ci

import android.content.Context
import com.stellar.autohub.data.GhJob
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** A downloaded job log, plus each step's own output when GitHub's log archive has it. */
class LoadedLog(
    val file: File,
    val lines: List<String>,
    /** Lines dropped from the start because the log was huge (the end is always kept). */
    val droppedLines: Int,
    /** Step number -> that step's output. */
    val steps: Map<Int, List<String>>,
    val sections: List<LogSection>,
)

/**
 * Downloads logs to a file first (big builds like native C++ can produce many MB of output),
 * then reads it on a background thread, keeping the last [MAX_LINES] lines.
 */
object Logs {
    private const val MAX_LINES = 60_000
    private const val MAX_STEP_LINES = 20_000

    suspend fun load(ctx: Context, api: GitHubApi, repo: RepoProfile, runId: Long, job: GhJob, progress: (String) -> Unit): LoadedLog {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${repo.name}-job${job.id}.log")
        progress("Downloading log…")
        api.download("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/jobs/${job.id}/logs",
            "application/vnd.github+json", file) { done, _ -> if (done > 1_000_000) progress("Downloading log… ${done / 1_000_000} MB") }

        progress("Reading log…")
        val (lines, dropped) = withContext(Dispatchers.IO) { readTail(file) }

        // Per-step output, from the run's log archive (best effort: not every archive has step files)
        val steps = HashMap<Int, List<String>>()
        if (job.conclusion != "success") {
            try {
                progress("Getting step logs…")
                val zip = File(dir, "${repo.name}-run$runId.zip")
                if (!zip.exists() || zip.length() == 0L) {
                    api.download("https://api.github.com/repos/${repo.owner}/${repo.name}/actions/runs/$runId/logs",
                        "application/vnd.github+json", zip) { _, _ -> }
                }
                withContext(Dispatchers.IO) { steps += readSteps(zip, job.name) }
            } catch (_: Exception) { }
        }
        val sections = withContext(Dispatchers.Default) { LogParser.sectionsOf(lines) }
        return LoadedLog(file, lines, dropped, steps, sections)
    }

    private fun readTail(file: File): Pair<List<String>, Int> {
        val buf = ArrayDeque<String>()
        var total = 0
        file.bufferedReader().useLines { seq ->
            for (l in seq) {
                buf.addLast(LogParser.cleanLine(if (l.length > 4000) l.take(4000) + " …" else l))
                total++
                if (buf.size > MAX_LINES) buf.removeFirst()
            }
        }
        return buf.toList() to (total - buf.size)
    }

    /** Archive entries look like "<job name>/<step number>_<step name>.txt". */
    private fun readSteps(zip: File, jobName: String): Map<Int, List<String>> {
        val out = HashMap<Int, List<String>>()
        val safeJob = jobName.replace('/', '_').replace(':', '_')
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                val folder = name.substringBefore('/', "")
                if (!e.isDirectory && name.contains('/') && (folder == jobName || folder == safeJob)) {
                    val num = name.substringAfter('/').substringBefore('_').toIntOrNull()
                    if (num != null) {
                        val lines = ArrayDeque<String>()
                        z.bufferedReader().lineSequence().forEach { l ->
                            lines.addLast(LogParser.cleanLine(if (l.length > 4000) l.take(4000) + " …" else l))
                            if (lines.size > MAX_STEP_LINES) lines.removeFirst()
                        }
                        out[num] = lines.toList()
                    }
                }
            }
        }
        return out
    }

    /**
     * The output of one step. Uses the step's own log file when available; otherwise guesses from
     * the job log (each executed step starts a "##[group]Run …" block, in order).
     */
    fun stepSection(log: LoadedLog, job: GhJob, stepNumber: Int): LogSection? {
        val step = job.steps.find { it.number == stepNumber } ?: return null
        log.steps[stepNumber]?.let { return LogParser.section("Step $stepNumber: ${step.name}", it, firstIndex = 1_000_000 + stepNumber * 100_000) }
        val ran = job.steps.filter { it.conclusion != "skipped" && it.name != "Set up job" && it.name != "Complete job" && !it.name.startsWith("Post ") }
        val idx = ran.indexOfFirst { it.number == stepNumber }
        val runSections = log.sections.drop(1) // first section is "Set up job"
        val s = runSections.getOrNull(idx)
        return s?.let { LogSection("Step $stepNumber: ${step.name}", it.lines) }
    }

    /** The failed step's output: exact when step logs exist, else the block with the error. */
    fun failedSection(log: LoadedLog, job: GhJob): LogSection? {
        val failed = job.steps.firstOrNull { it.conclusion == "failure" }
        if (failed != null) {
            log.steps[failed.number]?.let { return stepSection(log, job, failed.number) }
        }
        val bySections = LogParser.failedSection(log.sections)
        return if (failed != null && bySections != null) LogSection("Step ${failed.number}: ${failed.name}", bySections.lines) else bySections
    }
}
