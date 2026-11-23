package com.stellar.autohub.ci

import com.stellar.autohub.data.GhArtifact
import com.stellar.autohub.data.GhRelease
import com.stellar.autohub.data.GhRun
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi

class CleanupPlan(
    val runs: List<GhRun>,
    val artifacts: List<GhArtifact>,
    val releases: List<GhRelease>,
    val freedBytes: Long,
    val totalArtifactBytes: Long,
    val totalRuns: Int,
    val totalReleases: Int,
)

/** Deletes old workflow runs, artifacts and releases on GitHub, always keeping the newest ones. */
object Cleanup {

    suspend fun plan(api: GitHubApi, repo: RepoProfile, keepRuns: Int, keepReleases: Int): CleanupPlan {
        val runs = api.allRuns(repo.owner, repo.name).sortedByDescending { it.created_at ?: "" }
        val keptRunIds = runs.take(keepRuns).map { it.id }.toSet()
        val oldRuns = runs.drop(keepRuns).filter { it.status == "completed" }

        val artifacts = api.allArtifacts(repo.owner, repo.name)
        val live = artifacts.filter { !it.expired }
        val oldArtifacts = live.filter { a -> a.workflow_run?.id !in keptRunIds }

        val releases = api.releases(repo.owner, repo.name).sortedByDescending { it.created_at ?: "" }
        val oldReleases = releases.drop(keepReleases.coerceAtLeast(1))

        return CleanupPlan(
            runs = oldRuns,
            artifacts = oldArtifacts,
            releases = oldReleases,
            freedBytes = oldArtifacts.sumOf { it.size_in_bytes },
            totalArtifactBytes = live.sumOf { it.size_in_bytes },
            totalRuns = runs.size,
            totalReleases = releases.size,
        )
    }

    suspend fun execute(api: GitHubApi, repo: RepoProfile, plan: CleanupPlan, deleteTags: Boolean, progress: (String) -> Unit): String {
        var failed = 0
        plan.artifacts.forEachIndexed { i, a ->
            progress("Deleting artifacts ${i + 1}/${plan.artifacts.size}")
            try { api.deleteArtifact(repo.owner, repo.name, a.id) } catch (e: Exception) { failed++ }
        }
        plan.runs.forEachIndexed { i, r ->
            progress("Deleting old runs ${i + 1}/${plan.runs.size}")
            try { api.deleteRun(repo.owner, repo.name, r.id) } catch (e: Exception) { failed++ }
        }
        plan.releases.forEachIndexed { i, r ->
            progress("Deleting releases ${i + 1}/${plan.releases.size}")
            try {
                api.deleteRelease(repo.owner, repo.name, r.id)
                if (deleteTags) api.deleteTag(repo.owner, repo.name, r.tag_name)
            } catch (e: Exception) { failed++ }
        }
        val done = plan.artifacts.size + plan.runs.size + plan.releases.size - failed
        return if (failed == 0) "Cleaned up $done items" else "Cleaned up $done items, $failed failed"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
