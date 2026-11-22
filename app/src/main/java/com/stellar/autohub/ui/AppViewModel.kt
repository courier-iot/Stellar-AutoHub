package com.stellar.autohub.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.upload.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PreviewState(
    val repoId: String,
    val loading: Boolean = true,
    val error: String? = null,
    val plan: Plan? = null,
    val mode: UploadMode = UploadMode.PATCH,
    val selectedDeletes: Set<String> = emptySet(),
    val stripOverride: Boolean? = null,
    val message: String = "",
    val releaseExists: Boolean = false,
    val committing: Boolean = false,
    val progress: String = "",
    val doneSha: String? = null,
    val doneHistoryId: String? = null,
) {
    fun copy(
        loading: Boolean = this.loading, error: String? = this.error, plan: Plan? = this.plan,
        mode: UploadMode = this.mode, selectedDeletes: Set<String> = this.selectedDeletes,
        stripOverride: Boolean? = this.stripOverride, message: String = this.message,
        releaseExists: Boolean = this.releaseExists, committing: Boolean = this.committing,
        progress: String = this.progress, doneSha: String? = this.doneSha, doneHistoryId: String? = this.doneHistoryId,
    ) = PreviewState(repoId, loading, error, plan, mode, selectedDeletes, stripOverride, message, releaseExists,
        committing, progress, doneSha, doneHistoryId)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AutoHubApp

    /** What the user picked/shared, waiting to be previewed. */
    private val _payload = MutableStateFlow<Payload?>(null)
    val payload: StateFlow<Payload?> = _payload

    private val _loadingPayload = MutableStateFlow<String?>(null)
    val loadingPayload: StateFlow<String?> = _loadingPayload

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview

    /** Files shared into the app before a repo was picked. */
    private val _sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val sharedUris: StateFlow<List<Uri>> = _sharedUris

    private var remote: RemoteState? = null

    fun setShared(uris: List<Uri>) { _sharedUris.value = uris }
    fun clearShared() { _sharedUris.value = emptyList() }

    fun loadUris(uris: List<Uri>, targetFolder: String, onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _loadingPayload.value = "Reading files…"
            try {
                _payload.value = withContext(Dispatchers.IO) { PayloadReader.fromUris(app, uris, targetFolder) }
                onReady()
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't read those files")
            } finally { _loadingPayload.value = null }
        }
    }

    fun loadTree(uri: Uri, onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _loadingPayload.value = "Reading folder…"
            try {
                _payload.value = withContext(Dispatchers.IO) { PayloadReader.fromTree(app, uri) }
                onReady()
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't read that folder")
            } finally { _loadingPayload.value = null }
        }
    }

    /** Best guess at which saved repo a shared zip belongs to (by applicationId or folder name). */
    suspend fun guessRepo(uris: List<Uri>): RepoProfile? = withContext(Dispatchers.IO) {
        val repos = app.store.data.value.repos
        if (repos.isEmpty()) return@withContext null
        try {
            val p = PayloadReader.fromUris(app, uris, "")
            _payload.value = p
            val gradle = p.files.firstOrNull { f -> PathRules.GRADLE_FILES.any { f.path.endsWith(it) } }
            val appId = gradle?.let { PathRules.applicationId(String(it.bytes)) }
            val root = p.files.firstOrNull()?.path?.substringBefore('/')?.lowercase()
            repos.firstOrNull { appId != null && it.packageName == appId }
                ?: repos.firstOrNull { root != null && it.name.lowercase() == root }
        } catch (e: Exception) { null }
    }

    fun startPreview(repoId: String) {
        val repo = app.store.repo(repoId) ?: return
        _preview.value = PreviewState(repoId)
        viewModelScope.launch {
            try {
                remote = app.committer.loadRemote(repo)
                replan(null)
            } catch (e: Exception) {
                _preview.value = _preview.value?.copy(loading = false, error = e.message ?: "Couldn't read the repo")
            }
        }
    }

    fun setStrip(strip: Boolean) = replan(strip)

    private fun replan(strip: Boolean?) {
        val st = _preview.value ?: return
        val repo = app.store.repo(st.repoId) ?: return
        val p = _payload.value ?: return
        val r = remote ?: return
        viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) {
                Planner.plan(
                    p, r,
                    ignore = PathRules.parsePatterns(app.prefs.ignorePatterns),
                    protectedPatterns = PathRules.parsePatterns(repo.protectedPatterns),
                    strip = strip,
                    autoStrip = app.prefs.autoStrip,
                )
            }
            val mode = if (plan.looksLikeProject && plan.deletable.isNotEmpty()) UploadMode.MIRROR else UploadMode.PATCH
            val defaultDeletes = plan.deletable.filter { !it.protected }.map { it.path }.toSet()
            val message = when {
                plan.version != null -> "v${plan.version} via Stellar AutoHub"
                else -> "Update via Stellar AutoHub (${p.label})"
            }
            val releaseExists = plan.version?.let {
                try { app.api.releaseByTag(repo.owner, repo.name, "v$it") != null } catch (e: Exception) { false }
            } ?: false
            _preview.value = st.copy(
                loading = false, error = null, plan = plan, mode = mode,
                selectedDeletes = defaultDeletes, stripOverride = strip,
                message = st.message.ifEmpty { message }, releaseExists = releaseExists,
            )
            // Learn the app's package name for install checks
            if (plan.applicationId != null && repo.packageName != plan.applicationId) {
                app.store.upsertRepo(repo.copy(packageName = plan.applicationId))
            }
        }
    }

    fun setMode(m: UploadMode) { _preview.value = _preview.value?.copy(mode = m) }
    fun setMessage(m: String) { _preview.value = _preview.value?.copy(message = m) }
    fun toggleDelete(path: String) {
        val st = _preview.value ?: return
        _preview.value = st.copy(selectedDeletes = if (path in st.selectedDeletes) st.selectedDeletes - path else st.selectedDeletes + path)
    }
    fun setAllDeletes(on: Boolean) {
        val st = _preview.value ?: return
        _preview.value = st.copy(selectedDeletes = if (on) st.plan?.deletable?.map { it.path }?.toSet() ?: emptySet() else emptySet())
    }

    fun commit() {
        val st = _preview.value ?: return
        val plan = st.plan ?: return
        val repo = app.store.repo(st.repoId) ?: return
        val deletes = if (st.mode == UploadMode.MIRROR) st.selectedDeletes else emptySet()
        _preview.value = st.copy(committing = true, progress = "Starting…", error = null)
        viewModelScope.launch {
            try {
                val sha = app.committer.commit(repo, plan, deletes, st.message.ifBlank { "Update via Stellar AutoHub" }) { msg ->
                    _preview.value = _preview.value?.copy(progress = msg)
                }
                val entry = HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    repoId = repo.id,
                    time = System.currentTimeMillis(),
                    message = st.message,
                    commitSha = sha,
                    version = plan.version ?: "",
                    added = plan.added,
                    changed = plan.changed,
                    deleted = deletes.size,
                    status = if (app.prefs.watchBuilds) "queued" else "none",
                )
                app.store.addHistory(entry)
                if (app.prefs.watchBuilds) BuildWatcher.start(app, repo.id, repo.fullName, sha, entry.id)
                _payload.value = null
                _preview.value = _preview.value?.copy(committing = false, doneSha = sha, doneHistoryId = entry.id)
            } catch (e: Exception) {
                _preview.value = _preview.value?.copy(committing = false, error = e.message ?: "Commit failed")
            }
        }
    }

    fun clearPreview() { _preview.value = null }
}
