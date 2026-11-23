package com.stellar.autohub.editor

import android.content.Context
import com.stellar.autohub.data.GhTreeEntry
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File contents fetched from GitHub, cached by blob sha (content-addressed, so never stale).
 * Plus a short-lived per-branch tree cache for the file browser / search.
 */
class BlobCache(private val ctx: Context, private val api: GitHubApi) {
    private val dir get() = File(ctx.cacheDir, "blobs").apply { mkdirs() }
    private val trees = HashMap<String, Pair<Long, List<GhTreeEntry>>>()

    suspend fun bytes(repo: RepoProfile, sha: String): ByteArray = withContext(Dispatchers.IO) {
        val f = File(dir, sha)
        if (f.exists()) { f.setLastModified(System.currentTimeMillis()); return@withContext f.readBytes() }
        val b = api.blob(repo.owner, repo.name, sha)
        f.writeBytes(b)
        trim()
        b
    }

    suspend fun text(repo: RepoProfile, sha: String): String? = com.stellar.autohub.upload.PathRules.asText(bytes(repo, sha))

    /** Every entry of the branch (cached ~60s unless [force]). */
    suspend fun tree(repo: RepoProfile, branch: String = repo.branch, force: Boolean = false): List<GhTreeEntry> {
        val key = "${repo.id}@$branch"
        val hit = trees[key]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val head = api.branchHead(repo.owner, repo.name, branch) ?: return emptyList()
        val treeSha = api.commit(repo.owner, repo.name, head).tree.sha
        val entries = api.tree(repo.owner, repo.name, treeSha).tree
        trees[key] = System.currentTimeMillis() to entries
        return entries
    }

    fun invalidate(repo: RepoProfile) { trees.keys.filter { it.startsWith(repo.id + "@") }.forEach { trees.remove(it) } }

    /** Keeps the cache under ~60 MB by removing the least recently used files. */
    private fun trim() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total < 60L shl 20) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length(); f.delete()
            if (total < 40L shl 20) break
        }
    }

    fun clear() { dir.deleteRecursively(); trees.clear() }
    fun size(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
}
