package com.stellar.autohub.editor

import android.content.Context
import com.stellar.autohub.upload.Payload
import com.stellar.autohub.upload.RawFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** An edited, new, or deleted file that hasn't been committed yet. Kept on the phone. */
@Serializable
data class Draft(
    val repoId: String,
    val branch: String,
    val path: String,
    /** Blob sha of the file when editing started (null = new file). Used to spot conflicts. */
    val baseSha: String? = null,
    val content: String? = null,
    val deleted: Boolean = false,
    val updated: Long = System.currentTimeMillis(),
)

object Drafts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _all = MutableStateFlow<List<Draft>>(emptyList())
    val all: StateFlow<List<Draft>> = _all
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "drafts.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _all.value = try {
            val f = file(ctx)
            if (f.exists()) json.decodeFromString(ListSerializer(Draft.serializer()), f.readText()) else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Draft>) {
        _all.value = list
        val tmp = File(ctx.filesDir, "drafts.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Draft.serializer()), list))
        tmp.renameTo(file(ctx))
    }

    fun forRepo(repoId: String, branch: String) = _all.value.filter { it.repoId == repoId && it.branch == branch }.sortedBy { it.path }

    fun get(repoId: String, branch: String, path: String) =
        _all.value.find { it.repoId == repoId && it.branch == branch && it.path == path }

    fun put(ctx: Context, d: Draft) {
        load(ctx)
        save(ctx, _all.value.filterNot { it.repoId == d.repoId && it.branch == d.branch && it.path == d.path } + d)
    }

    fun discard(ctx: Context, repoId: String, branch: String, path: String) {
        load(ctx)
        save(ctx, _all.value.filterNot { it.repoId == repoId && it.branch == branch && it.path == path })
    }

    fun clear(ctx: Context, repoId: String, branch: String, paths: Set<String>) {
        load(ctx)
        save(ctx, _all.value.filterNot { it.repoId == repoId && it.branch == branch && it.path in paths })
    }

    fun clearRepo(ctx: Context, repoId: String) {
        load(ctx)
        save(ctx, _all.value.filterNot { it.repoId == repoId })
    }

    /** All drafts of a repo/branch as one patch upload (one commit). */
    fun payload(repoId: String, branch: String, only: Set<String>? = null): Payload {
        val list = forRepo(repoId, branch).filter { only == null || it.path in only }
        return Payload(
            files = list.filter { !it.deleted && it.content != null }.map { RawFile(it.path, it.content!!.toByteArray()) },
            label = "${list.size} edited file(s)",
            structured = false,
            deletes = list.filter { it.deleted }.map { it.path },
            origin = "drafts",
        )
    }
}
