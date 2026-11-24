package com.stellar.autohub.ci

import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi
import com.stellar.autohub.upload.Payload
import com.stellar.autohub.upload.RawFile

/** One thing to fix in a workflow. [from]/[to] are set when AutoHub can fix it automatically. */
class HealthIssue(
    val file: String,
    val line: Int,
    val message: String,
    val severity: String, // "warn" | "info"
    val from: String? = null,
    val to: String? = null,
)

class HealthReport(
    val issues: List<HealthIssue>,
    val texts: Map<String, String>,
    val actionsChecked: Int,
    /** A ready build workflow to add when the repo has none (Android / Electron). */
    val addWorkflow: String? = null,
) {
    val fixable get() = issues.filter { (it.from != null && it.to != null) || (it.file == ".github/workflows/build.yml" && addWorkflow != null) }

    /** A loose-files upload containing the fixed workflow files, ready for the normal preview. */
    fun fixPayload(): Payload? {
        val byFile = fixable.filter { it.from != null }.groupBy { it.file }
        if (byFile.isEmpty() && addWorkflow == null) return null
        val files = byFile.mapNotNull { (path, issues) ->
            var t = texts[path] ?: return@mapNotNull null
            issues.forEach { t = t.replace(it.from!!, it.to!!) }
            RawFile(path, t.toByteArray())
        } + listOfNotNull(addWorkflow?.let { RawFile(".github/workflows/build.yml", it.toByteArray()) })
        return Payload(files, "Workflow updates", structured = false)
    }
}

object WorkflowHealth {
    private val uses = Regex("""uses:\s*["']?([\w.\-]+/[\w.\-]+)((?:/[\w.\-]+)*)@([\w.\-]+)""")
    private val sha = Regex("^[0-9a-f]{40}$")
    private val oldCommands = listOf("::set-output", "::save-state", "::set-env", "::add-path")

    private fun major(tag: String): Int? = tag.trimStart('v', 'V').substringBefore('.').toIntOrNull()

    suspend fun check(api: GitHubApi, repo: RepoProfile): HealthReport {
        val texts = linkedMapOf<String, String>()
        for (path in api.workflowFiles(repo.owner, repo.name, repo.branch)) {
            api.rawFile(repo.owner, repo.name, path, repo.branch)?.let { texts[path] = it }
        }
        val issues = mutableListOf<HealthIssue>()
        val latest = HashMap<String, String?>()

        for ((path, text) in texts) {
            text.lines().forEachIndexed { i, line ->
                val l = line.substringBefore(" #")
                oldCommands.firstOrNull { l.contains(it) }?.let {
                    issues += HealthIssue(path, i + 1, "Uses the removed \"$it\" command. Write to \$GITHUB_OUTPUT / \$GITHUB_ENV instead.", "warn")
                }
                val m = uses.find(l) ?: return@forEachIndexed
                val action = m.groupValues[1]
                val sub = m.groupValues[2]
                val ref = m.groupValues[3]
                if (action.startsWith("./") || action.contains("docker:")) return@forEachIndexed
                if (sha.matches(ref)) return@forEachIndexed // pinned to a commit on purpose
                val used = major(ref) ?: return@forEachIndexed
                val tag = latest.getOrPut(action) {
                    try { api.latestRelease(action.substringBefore('/'), action.substringAfter('/'))?.tag_name } catch (e: Exception) { null }
                } ?: return@forEachIndexed
                val newest = major(tag) ?: return@forEachIndexed
                if (newest > used) {
                    val newRef = if (ref.contains('.')) tag else "v$newest"
                    issues += HealthIssue(
                        path, i + 1, "$action$sub@$ref is outdated. Latest is $tag.", if (newest - used >= 2) "warn" else "info",
                        from = "$action$sub@$ref", to = "$action$sub@$newRef",
                    )
                }
            }
        }

        // GitHub's own deprecation warnings from the latest run
        try {
            val run = api.recentRuns(repo.owner, repo.name, repo.branch).firstOrNull { it.status == "completed" }
            if (run != null) {
                val seen = HashSet<String>()
                for (job in api.jobs(repo.owner, repo.name, run.id)) {
                    for (a in api.annotations(repo.owner, repo.name, job.id)) {
                        val msg = a.message?.trim() ?: continue
                        if (a.annotation_level == "warning" && (msg.contains("deprecat", true) || msg.contains("Node.js", true) || msg.contains("will be removed", true))) {
                            if (seen.add(msg.take(120))) issues += HealthIssue(job.name, 0, "GitHub warning: ${msg.take(300)}", "warn")
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        var add: String? = null
        if (texts.isEmpty()) {
            val kind = com.stellar.autohub.upload.ProjectKind.of(repo.kind)
            add = com.stellar.autohub.editor.Templates.workflowFor(kind, repo.name)
            issues += HealthIssue(
                if (add != null) ".github/workflows/build.yml" else "—", 0,
                "No workflow files found in .github/workflows, so nothing builds on push." +
                    if (add != null) " AutoHub can add the standard ${kind.label} build (releases vX.Y.Z from main)." else "",
                "warn",
            )
        }
        return HealthReport(issues.distinctBy { it.file + it.line + it.message }, texts, latest.size, add)
    }
}
