package com.stellar.autohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/** Saved repos + upload history, persisted as one small JSON file. */
class Store(context: Context) {
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

    fun history(repoId: String): List<HistoryEntry> =
        _data.value.history.filter { it.repoId == repoId }.sortedByDescending { it.time }
}
