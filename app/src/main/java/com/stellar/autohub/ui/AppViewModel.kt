package com.stellar.autohub.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ci.BuildWatcher
import com.stellar.autohub.data.HistoryEntry
import com.stellar.autohub.data.QueuedUpload
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubException
import com.stellar.autohub.upload.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class PreviewState(
    val repoId: String,
    val loading: Boolean = true,
    val error: String? = null,
    val plan: Plan? = null,
    val mode: UploadMode = UploadMode.PATCH,
    val selectedDeletes: Set<String> = emptySet(),
    val stripOverride: Boolean? = null,
    val message: String = "",
    val releaseExists: Boolean = false,
    val latestRelease: String? = null,
    val excluded: Set<String> = emptySet(),
    val allowedLeaks: Set<String> = emptySet(),
    val committing: Boolean = false,
    val progress: String = "",
    val canQueue: Boolean = false,
    val offline: Boolean = false,
    /** Set when this preview is reviewing an item from the offline queue. */
    val queuedId: String? = null,
    val doneSha: String? = null,
    val doneHistoryId: String? = null,
    val queuedDone: Boolean = false,
) {
    /** The plan as it will actually be committed. */
    val effective: Plan? get() = plan?.excluding(excluded)

    val unresolvedLeaks: List<Leak>
        get() = effective?.leaks?.filter { it.key() !in allowedLeaks } ?: emptyList()

    /** Version the "bump" button would set, when the upload's version is already released (or older). */
    val bumpTarget: String?
        get() {
            val pl = plan ?: return null
            val v = pl.version ?: return null
            if (pl.gradlePath == null) return null
            val latest = latestRelease
            val behind = latest != null && PathRules.compareVersions(v, latest) <= 0
            if (!releaseExists && !behind) return null
            val base = if (latest != null && PathRules.compareVersions(latest, v) > 0) latest else v
            return PathRules.nextPatch(base)
        }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AutoHubApp

    private val _payload = MutableStateFlow<Payload?>(null)
    val payload: StateFlow<Payload?> = _payload

    private val _loadingPayload = MutableStateFlow<String?>(null)
    val loadingPayload: StateFlow<String?> = _loadingPayload

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview

    private val _sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val sharedUris: StateFlow<List<Uri>> = _sharedUris

    private var remote: RemoteState? = null
    private var pendingQueued: QueuedUpload? = null

    fun setShared(uris: List<Uri>) { _sharedUris.value = uris }
    fun clearShared() { _sharedUris.value = emptyList() }

    /** Use a ready-made payload (workflow fixes, queued uploads). */
    fun setPayload(p: Payload) { _payload.value = p; _preview.value = null; pendingQueued = null }

    fun loadUris(uris: List<Uri>, targetFolder: String, onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _loadingPayload.value = "Reading files…"
            try {
                _payload.value = withContext(Dispatchers.IO) { PayloadReader.fromUris(app, uris, targetFolder) }
                _preview.value = null; pendingQueued = null
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
                _preview.value = null; pendingQueued = null
                onReady()
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't read that folder")
            } finally { _loadingPayload.value = null }
        }
    }

    /** Opens a queued upload in the normal preview, keeping its earlier choices. */
    fun reviewQueued(m: QueuedUpload, onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _payload.value = withContext(Dispatchers.IO) { UploadQueue.payload(app, m) }
                _preview.value = null
                pendingQueued = m
                onReady()
            } catch (e: Exception) { onError(e.message ?: "Couldn't open that upload") }
        }
    }

    suspend fun guessRepo(uris: List<Uri>): RepoProfile? = withContext(Dispatchers.IO) {
        val repos = app.store.data.value.repos
        if (repos.isEmpty()) return@withContext null
        try {
            val p = PayloadReader.fromUris(app, uris, "")
            _payload.value = p
            _preview.value = null; pendingQueued = null
            val gradle = p.files.firstOrNull { f -> PathRules.GRADLE_FILES.any { f.path.endsWith(it) } }
            val appId = gradle?.let { PathRules.applicationId(String(it.bytes)) }
            val root = p.files.firstOrNull()?.path?.substringBefore('/')?.lowercase()
            repos.firstOrNull { appId != null && it.packageName == appId }
                ?: repos.firstOrNull { root != null && it.name.lowercase() == root }
        } catch (e: Exception) { null }
    }

    fun startPreview(repoId: String) {
        val repo = app.store.repo(repoId) ?: return
        val q = pendingQueued
        _preview.value = PreviewState(repoId, queuedId = q?.id, stripOverride = q?.stripOverride,
            message = q?.message ?: "", excluded = q?.excluded?.toSet() ?: emptySet(),
            allowedLeaks = q?.allowedLeaks?.toSet() ?: emptySet())
        viewModelScope.launch {
            try {
                remote = app.committer.loadRemote(repo)
                replan(q?.stripOverride, applyQueued = q)
            } catch (e: Exception) {
                val offline = e !is GitHubException
                _preview.value = _preview.value?.copy(
                    loading = false, offline = offline,
                    error = if (offline) "Can't reach GitHub right now. You can queue this upload; it's sent automatically when you're back online."
                    else e.message ?: "Couldn't read the repo",
                )
            }
        }
    }

    fun setStrip(strip: Boolean) = replan(strip)

    private fun replan(strip: Boolean?, applyQueued: QueuedUpload? = null) {
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
                ).let { if (app.prefs.scanSecrets) it else it.withoutLeaks() }
            }
            var mode = if (plan.looksLikeProject && plan.deletable.isNotEmpty()) UploadMode.MIRROR else UploadMode.PATCH
            var deletes = plan.deletable.filter { !it.protected }.map { it.path }.toSet()
            if (applyQueued != null) {
                mode = if (applyQueued.mode == "MIRROR") UploadMode.MIRROR else UploadMode.PATCH
                if (applyQueued.reviewed) deletes = applyQueued.deletes.toSet().intersect(plan.deletable.map { it.path }.toSet())
            }
            val message = plan.version?.let { "v$it via Stellar AutoHub" } ?: "Update via Stellar AutoHub (${p.label})"
            val (exists, latest) = plan.version?.let {
                try {
                    val tagExists = app.api.releaseByTag(repo.owner, repo.name, "v$it") != null
                    tagExists to app.api.latestRelease(repo.owner, repo.name)?.tag_name
                } catch (e: Exception) { false to null }
            } ?: (false to null)
            _preview.value = (_preview.value ?: st).copy(
                loading = false, error = null, plan = plan, mode = mode,
                selectedDeletes = deletes, stripOverride = strip,
                message = st.message.ifEmpty { message }, releaseExists = exists, latestRelease = latest,
            )
            if (plan.applicationId != null && repo.packageName != plan.applicationId) {
                app.store.upsertRepo(repo.copy(packageName = plan.applicationId))
            }
        }
    }

    private fun Plan.withoutLeaks() = Plan(files, deletable, skipped, strippedRoot, canStrip, looksLikeProject, version,
        applicationId, warnings, remoteHead, remoteTreeSha, emptyList(), gradlePath, requestedDeletes, checks)

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

    // ---------- Secret warnings ----------

    fun toggleExclude(path: String) {
        val st = _preview.value ?: return
        _preview.value = st.copy(excluded = if (path in st.excluded) st.excluded - path else st.excluded + path)
    }

    fun allowLeak(key: String) {
        val st = _preview.value ?: return
        _preview.value = st.copy(allowedLeaks = st.allowedLeaks + key)
    }

    // ---------- Version bump ----------

    /** Rewrites versionName/versionCode in the upload's gradle file, then re-runs the preview. */
    fun bumpVersion() {
        val st = _preview.value ?: return
        val plan = st.plan ?: return
        val target = st.bumpTarget ?: return
        val gp = plan.gradlePath ?: return
        val p = _payload.value ?: return
        val match = { path: String ->
            val n = PathRules.normalize(path)
            n == gp || (plan.strippedRoot != null && n == "${plan.strippedRoot}/$gp")
        }
        val files = p.files.map { f ->
            if (match(f.path)) RawFile(f.path, PathRules.bumpGradle(String(f.bytes), target).toByteArray()) else f
        }
        _payload.value = Payload(files, p.label, p.structured)
        val oldDefault = plan.version?.let { "v$it via Stellar AutoHub" }
        _preview.value = st.copy(message = if (st.message == oldDefault || st.message.isEmpty()) "v$target via Stellar AutoHub" else st.message)
        replan(st.stripOverride)
    }

    // ---------- Commit / queue ----------

    fun commit() {
        val st = _preview.value ?: return
        val plan = st.effective ?: return
        val repo = app.store.repo(st.repoId) ?: return
        val deletes = (if (st.mode == UploadMode.MIRROR) st.selectedDeletes else emptySet()) + plan.requestedDeletes
        if (!UploadQueue.isOnline(app)) { queueCurrent(); return }
        _preview.value = st.copy(committing = true, progress = "Starting…", error = null, canQueue = false)
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
                st.queuedId?.let { UploadQueue.remove(app, it) }
                if (_payload.value?.origin == "drafts") {
                    com.stellar.autohub.editor.Drafts.clear(app, repo.id, repo.branch,
                        plan.files.map { it.path }.toSet() + plan.requestedDeletes)
                }
                if (app.prefs.watchBuilds) BuildWatcher.start(app, repo.id, repo.fullName, sha, entry.id)
                _payload.value = null
                pendingQueued = null
                _preview.value = _preview.value?.copy(committing = false, doneSha = sha, doneHistoryId = entry.id)
            } catch (e: GitHubException) {
                _preview.value = _preview.value?.copy(committing = false, error = e.message ?: "Commit failed")
            } catch (e: IOException) {
                _preview.value = _preview.value?.copy(committing = false, canQueue = true,
                    error = "Connection lost (${e.message ?: "network error"}). Nothing was changed on GitHub. Queue it to send automatically later?")
            } catch (e: Exception) {
                _preview.value = _preview.value?.copy(committing = false, error = e.message ?: "Commit failed")
            }
        }
    }

    /** Saves the upload with all the choices made in the preview, to send once online. */
    fun queueCurrent() {
        val st = _preview.value ?: return
        val p = _payload.value ?: return
        val reviewed = st.plan != null
        val m = QueuedUpload(
            id = st.queuedId ?: UUID.randomUUID().toString(),
            repoId = st.repoId,
            created = System.currentTimeMillis(),
            label = p.label,
            message = st.message.ifBlank { "Update via Stellar AutoHub (${p.label})" },
            structured = p.structured,
            mode = if (reviewed && st.mode == UploadMode.MIRROR) "MIRROR" else "PATCH",
            deletes = if (reviewed && st.mode == UploadMode.MIRROR) st.selectedDeletes.toList() else emptyList(),
            requestedDeletes = p.deletes,
            origin = p.origin,
            stripOverride = st.stripOverride,
            excluded = st.excluded.toList(),
            allowedLeaks = st.allowedLeaks.toList(),
            reviewed = reviewed,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { UploadQueue.add(app, p, m) }
            _payload.value = null
            pendingQueued = null
            _preview.value = st.copy(queuedDone = true, error = null)
        }
    }

    fun clearPreview() { _preview.value = null }

    // ---------- Edit before upload ----------

    /** Blob sha of a file in the repo as it was when the preview loaded. */
    fun remoteSha(path: String): String? = remote?.entries?.find { it.path == path && it.type == "blob" }?.sha

    /** Text of a file in the pending upload (by its repo path after strip), or null if binary/missing. */
    fun payloadText(path: String): String? {
        val st = _preview.value ?: return null
        val f = st.plan?.files?.find { it.path == path } ?: return null
        return PathRules.asText(f.bytes)
    }

    /** Replaces a file's content inside the pending upload, then refreshes the preview. */
    fun updatePayloadFile(path: String, text: String) {
        val st = _preview.value ?: return
        val plan = st.plan ?: return
        val p = _payload.value ?: return
        val match = { raw: String ->
            val n = PathRules.normalize(raw)
            n == path || (plan.strippedRoot != null && n == "${plan.strippedRoot}/$path")
        }
        var found = false
        val files = p.files.map { f -> if (match(f.path)) { found = true; RawFile(f.path, text.toByteArray()) } else f }
        _payload.value = Payload(if (found) files else files + RawFile(path, text.toByteArray()), p.label, p.structured, p.deletes, p.origin)
        replan(st.stripOverride)
    }
}
