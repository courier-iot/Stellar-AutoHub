package com.stellar.autohub.ci

import com.stellar.autohub.data.GhAnnotation
import com.stellar.autohub.data.GhJob
import com.stellar.autohub.data.GhRun
import com.stellar.autohub.data.RepoProfile

enum class LineKind { NORMAL, ERROR, WARNING, HEADER }

class LogLine(val index: Int, val text: String, val kind: LineKind)

class LogSection(val title: String, val lines: List<LogLine>) {
    val hasError get() = lines.any { it.kind == LineKind.ERROR }
}

object LogParser {
    private val timestamp = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z\s?""")
    private val strongError = listOf(
        "##[error]", "e: ", "error:", "FAILURE:", "BUILD FAILED", "What went wrong", "fatal:", "Unresolved reference",
        "AssertionError", "AssertionFailedError", "ComparisonFailure", "expected:<", "There were failing tests",
        "tests completed,", "Test failed", "CMake Error", "make: ***", "ninja: build stopped", "Segmentation fault",
        "No such file or directory", "command not found", "Permission denied",
        "npm ERR!", "npm error", "SyntaxError", "TypeError", "ReferenceError", "Cannot find module", "error TS",
        "⨯", "ELIFECYCLE", "Module not found", "error CS", "error MSB", "Traceback (most recent call last)",
    )
    private val warnHints = listOf("##[warning]", "w: ", "warning:")

    fun clean(raw: String): List<String> = raw.lines().map { cleanLine(it) }

    fun cleanLine(l: String): String = l.replace(timestamp, "").trimEnd('\r').removePrefix("\uFEFF")

    fun kind(line: String): LineKind {
        val t = line.trimStart()
        return when {
            t.startsWith("##[group]") -> LineKind.HEADER
            strongError.any { t.startsWith(it) || (it.length > 5 && t.contains(it)) } -> LineKind.ERROR
            t.contains("Exception") && !t.contains("--stacktrace") -> LineKind.ERROR
            t.endsWith(" FAILED") || t.startsWith("Execution failed") || t.contains("Could not resolve") -> LineKind.ERROR
            warnHints.any { t.startsWith(it) } -> LineKind.WARNING
            else -> LineKind.NORMAL
        }
    }

    /** Splits a job log into sections at each "##[group]Run …" marker (one per step, roughly). */
    fun sections(raw: String): List<LogSection> = sectionsOf(clean(raw))

    /** A whole step's output as one section (from the per-step log files). */
    fun section(title: String, lines: List<String>, firstIndex: Int = 0): LogSection =
        LogSection(title, lines.mapIndexed { i, l -> LogLine(firstIndex + i, l.replace("##[group]", "▸ ").replace("##[endgroup]", ""), kind(l)) })

    fun sectionsOf(lines: List<String>): List<LogSection> {
        val out = mutableListOf<LogSection>()
        var title = "Set up job"
        var cur = mutableListOf<LogLine>()
        lines.forEachIndexed { i, l ->
            val t = l.trimStart()
            if (t.startsWith("##[group]Run ")) {
                if (cur.isNotEmpty()) out += LogSection(title, cur)
                title = t.removePrefix("##[group]Run ").take(80)
                cur = mutableListOf()
            }
            cur += LogLine(i, l.replace("##[group]", "▸ ").replace("##[endgroup]", ""), kind(l))
        }
        if (cur.isNotEmpty()) out += LogSection(title, cur)
        return out
    }

    /** The section that failed: the first with an ##[error], else the last with any error. */
    fun failedSection(sections: List<LogSection>): LogSection? =
        sections.firstOrNull { s -> s.lines.any { it.text.trimStart().startsWith("##[error]") } }
            ?: sections.lastOrNull { it.hasError }

    /** Error lines with a little context, de-duplicated. */
    fun errorExcerpt(section: LogSection, context: Int = 2, max: Int = 120): List<LogLine> {
        val idx = section.lines.indices.filter { section.lines[it].kind == LineKind.ERROR }
        val keep = sortedSetOf<Int>()
        for (i in idx) for (j in (i - context)..(i + context)) if (j in section.lines.indices) keep += j
        return keep.map { section.lines[it] }.take(max)
    }

    /** Compact report to paste to Claude. */
    fun report(
        repo: RepoProfile, run: GhRun, job: GhJob?, annotations: List<GhAnnotation>,
        section: LogSection?, commitMessage: String?,
    ): String = buildString {
        appendLine("Stellar AutoHub build report")
        appendLine("Repo: ${repo.fullName} (branch ${repo.branch})")
        appendLine("Commit: ${run.head_sha.take(7)}${commitMessage?.let { " \"${it.lineSequence().first()}\"" } ?: ""}")
        appendLine("Workflow: ${run.name ?: "?"} · attempt ${run.run_attempt} · result: ${run.conclusion ?: run.status}")
        appendLine("Run: ${run.html_url}")
        if (job != null) {
            val step = job.steps.firstOrNull { it.conclusion == "failure" }
            appendLine("Failed job: ${job.name}${step?.let { " · step ${it.number}: ${it.name}" } ?: ""}")
        }
        val ann = annotations.filter { it.annotation_level == "failure" || it.annotation_level == "warning" }
        if (ann.isNotEmpty()) {
            appendLine()
            appendLine("Annotations:")
            ann.take(15).forEach { a ->
                val loc = a.path?.takeIf { it != ".github" }?.let { p -> " ($p${a.start_line?.let { ":$it" } ?: ""})" } ?: ""
                appendLine("- [${a.annotation_level}]$loc ${a.message?.trim()?.take(400)}")
            }
        }
        if (section != null) {
            val errs = errorExcerpt(section, context = 1, max = 60)
            if (errs.isNotEmpty()) {
                appendLine()
                appendLine("Error lines (step: ${section.title}):")
                errs.forEach { appendLine(it.text) }
            }
            appendLine()
            appendLine("Last lines of the failed step:")
            section.lines.takeLast(60).forEach { appendLine(it.text) }
        }
    }.let { if (it.length > 14_000) it.take(14_000) + "\n…(trimmed)" else it }
}
