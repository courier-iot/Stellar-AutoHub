package com.stellar.autohub.upload

import android.util.Base64
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi
import com.stellar.autohub.github.GitHubException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a [Plan] into ONE commit using the Git Data API:
 * text files go inline in the tree (no extra requests), binaries become blobs,
 * deletions are tree entries with sha = null. No file-count limit.
 */
class Committer(private val api: GitHubApi) {

    suspend fun loadRemote(repo: RepoProfile): RemoteState {
        val head = api.branchHead(repo.owner, repo.name, repo.branch)
            ?: return RemoteState(null, null, emptyList(), false)
        val commit = api.commit(repo.owner, repo.name, head)
        val tree = api.tree(repo.owner, repo.name, commit.tree.sha)
        return RemoteState(head, commit.tree.sha, tree.tree, tree.truncated)
    }

    /**
     * @return the new commit sha.
     */
    suspend fun commit(
        repo: RepoProfile,
        plan: Plan,
        deletions: Set<String>,
        message: String,
        progress: (String) -> Unit,
    ): String {
        val owner = repo.owner
        val name = repo.name
        var toSend = plan.files.filter { it.status != FileStatus.UNCHANGED }
        val toDelete = plan.deletable.filter { it.path in deletions }
        if (toSend.isEmpty() && toDelete.isEmpty()) throw IllegalStateException("Nothing changed, so there's nothing to commit.")

        // Fresh check that nobody pushed since the preview was made
        var head = api.branchHead(owner, name, repo.branch)
        var baseTree = plan.remoteTreeSha
        if (head != plan.remoteHead) {
            throw IllegalStateException("The branch changed since this preview was made. Go back and preview again.")
        }

        // Empty repo: the Git Data API needs at least one commit, so seed with one file
        if (head == null) {
            val seed = toSend.minByOrNull { it.bytes.size } ?: throw IllegalStateException("Empty repo and nothing to add.")
            progress("Starting empty repo with ${seed.path}…")
            api.putContents(owner, name, seed.path, Base64.encodeToString(seed.bytes, Base64.NO_WRAP), repo.branch, message)
            head = api.branchHead(owner, name, repo.branch) ?: throw IllegalStateException("Couldn't create the first commit.")
            baseTree = api.commit(owner, name, head).tree.sha
            toSend = toSend.filterNot { it === seed }
            if (toSend.isEmpty() && toDelete.isEmpty()) return head
        }

        // 1. Blobs for binary files (text goes inline)
        val texts = toSend.associateWith { PathRules.asText(it.bytes) }
        val binaries = toSend.filter { texts[it] == null }
        val blobShas = HashMap<String, String>()
        if (binaries.isNotEmpty()) {
            val sem = Semaphore(3)
            var done = 0
            coroutineScope {
                binaries.map { f ->
                    async {
                        sem.withPermit {
                            val sha = api.createBlob(owner, name, Base64.encodeToString(f.bytes, Base64.NO_WRAP))
                            synchronized(blobShas) {
                                blobShas[f.path] = sha
                                done++
                                progress("Uploading binary files $done/${binaries.size}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // 2. Tree(s). Split into chunks so a huge first upload doesn't hit request size limits.
        val entries = mutableListOf<JsonObject>()
        for (d in toDelete) {
            entries += buildJsonObject {
                put("path", d.path); put("mode", d.mode); put("type", "blob"); put("sha", JsonNull)
            }
        }
        for (f in toSend) {
            val text = texts[f]
            entries += buildJsonObject {
                put("path", f.path); put("mode", f.mode); put("type", "blob")
                if (text != null) put("content", text) else put("sha", blobShas.getValue(f.path))
            }
        }
        val chunks = mutableListOf<List<JsonObject>>()
        var cur = mutableListOf<JsonObject>()
        var size = 0
        for (e in entries) {
            val len = e.toString().length
            if (cur.isNotEmpty() && (size + len > 6_000_000 || cur.size >= 2000)) {
                chunks += cur; cur = mutableListOf(); size = 0
            }
            cur += e; size += len
        }
        if (cur.isNotEmpty()) chunks += cur

        var tree = baseTree
        chunks.forEachIndexed { i, chunk ->
            progress(if (chunks.size == 1) "Building file tree…" else "Building file tree ${i + 1}/${chunks.size}…")
            tree = try {
                api.createTree(owner, name, tree, JsonArray(chunk))
            } catch (e: GitHubException) {
                if (e.code == 422 && toDelete.isNotEmpty())
                    throw GitHubException(422, "${e.message}\nA file marked for deletion may already be gone. Preview again.")
                throw e
            }
        }

        // 3. Commit + move the branch
        progress("Creating commit…")
        val commitSha = api.createCommit(owner, name, message, tree!!, head)
        progress("Updating ${repo.branch}…")
        try {
            api.updateRef(owner, name, repo.branch, commitSha)
        } catch (e: GitHubException) {
            val hint = if (plan.files.any { it.path.startsWith(".github/workflows/") && it.status != FileStatus.UNCHANGED })
                "\nIf this mentions workflows: your token is missing the 'workflow' permission." else ""
            throw GitHubException(e.code, (e.message ?: "") + hint)
        }
        return commitSha
    }
}
