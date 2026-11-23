package com.stellar.autohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/** Saved repos + upload history, persisted as one small JSON file. */
class Store(context: Context) {
    private val appCtx = context.applicationContext
    private val file = File(context.filesDir, "store.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<StoreData> = _data

    private fun load(): StoreData = try {
        if (file.exists()) json.decodeFromString(StoreData.serializer(), file.readText()) else StoreData()
    } catch (e: Exception) {
        StoreData()
    }

    @Synchronized
    private fun update(block: (StoreData) -> StoreData) {
        val next = block(_data.value)
        _data.value = next
        val tmp = File(file.parentFile, "store.json.tmp")
        tmp.writeText(json.encodeToString(StoreData.serializer(), next))
        tmp.renameTo(file)
        com.stellar.autohub.widget.BuildWidget.refresh(appCtx, next)
    }

    fun repo(id: String): RepoProfile? = _data.value.repos.find { it.id == id }

    fun upsertRepo(repo: RepoProfile) = update { d ->
        val list = d.repos.filterNot { it.id == repo.id } + repo
        d.copy(repos = list.sortedBy { it.fullName.lowercase() })
    }

    fun removeRepo(id: String) = update { d ->
        d.copy(repos = d.repos.filterNot { it.id == id }, history = d.history.filterNot { it.repoId == id })
    }

    fun addHistory(entry: HistoryEntry) = update { d ->
        val mine = (listOf(entry) + d.history.filter { it.repoId == entry.repoId }).take(50)
        d.copy(history = mine + d.history.filterNot { it.repoId == entry.repoId })
    }

    fun updateHistory(id: String, block: (HistoryEntry) -> HistoryEntry) = update { d ->
        d.copy(history = d.history.map { if (it.id == id) block(it) else it })
    }

    /** Merges a backup in: repos matched by owner/name, history de-duplicated by id. Returns repos added. */
    fun restore(other: StoreData): Int {
        var added = 0
        update { d ->
            val repos = d.repos.toMutableList()
            val idMap = HashMap<String, String>()
            for (r in other.repos) {
                val existing = repos.find { it.fullName.equals(r.fullName, true) }
                if (existing != null) idMap[r.id] = existing.id else { repos += r; idMap[r.id] = r.id; added++ }
            }
            val known = d.history.map { it.id }.toSet()
            val hist = d.history + other.history.filter { it.id !in known && idMap.containsKey(it.repoId) }
                .map { it.copy(repoId = idMap.getValue(it.repoId)) }
            d.copy(repos = repos.sortedBy { it.fullName.lowercase() }, history = hist)
        }
        return added
    }

    fun history(repoId: String): List<HistoryEntry> =
        _data.value.history.filter { it.repoId == repoId }.sortedByDescending { it.time }
}
