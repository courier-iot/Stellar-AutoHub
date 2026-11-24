package com.stellar.autohub.data

import kotlinx.serialization.Serializable

/** A repo the user has saved in AutoHub. */
@Serializable
data class RepoProfile(
    val id: String,
    val owner: String,
    val name: String,
    val branch: String = "main",
    val packageName: String = "",
    /** Comma/newline separated globs that are never deleted in Mirror mode. */
    val protectedPatterns: String = "README*\nLICENSE*",
    /** The repo's main branch (PR target). Empty = unknown, treat [branch] as default. */
    val defaultBranch: String = "",
    /** No build notifications for this repo. */
    val muted: Boolean = false,
    /** android | electron | node | dotnet | python | cpp | "" (unknown) */
    val kind: String = "",
) {
    val baseBranch: String get() = defaultBranch.ifEmpty { branch }
    val fullName: String get() = "$owner/$name"
}

@Serializable
data class HistoryEntry(
    val id: String,
    val repoId: String,
    val time: Long,
    val message: String,
    val commitSha: String,
    val version: String = "",
    val added: Int = 0,
    val changed: Int = 0,
    val deleted: Int = 0,
    val runId: Long = 0,
    /** queued | in_progress | success | failure | cancelled | none */
    val status: String = "queued",
)

@Serializable
data class StoreData(
    val repos: List<RepoProfile> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

// ---------- GitHub DTOs ----------

@Serializable
data class GhUser(val login: String, val name: String? = null)

@Serializable
data class GhOwner(val login: String)

@Serializable
data class GhRepo(
    val name: String,
    val full_name: String,
    val owner: GhOwner,
    val default_branch: String = "main",
    val private: Boolean = false,
    val pushed_at: String? = null,
)

@Serializable
data class GhObjectRef(val sha: String)

@Serializable
data class GhRef(val ref: String, val `object`: GhObjectRef)

@Serializable
data class GhTreeRef(val sha: String)

@Serializable
data class GhCommit(val sha: String, val tree: GhTreeRef, val parents: List<GhSha> = emptyList(), val message: String = "")

@Serializable
data class GhTreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
    val size: Long? = null,
)

@Serializable
data class GhTree(val sha: String, val tree: List<GhTreeEntry> = emptyList(), val truncated: Boolean = false)

@Serializable
data class GhSha(val sha: String)

@Serializable
data class GhRun(
    val id: Long,
    val updated_at: String? = null,
    val run_started_at: String? = null,
    val event: String? = null,
    val head_branch: String? = null,
    val name: String? = null,
    val display_title: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    val html_url: String = "",
    val head_sha: String = "",
    val run_attempt: Int = 1,
    val created_at: String? = null,
)

@Serializable
data class GhRuns(val workflow_runs: List<GhRun> = emptyList(), val total_count: Int = 0)

@Serializable
data class GhStep(
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    val number: Int = 0,
)

@Serializable
data class GhJob(
    val id: Long,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    val html_url: String? = null,
    val steps: List<GhStep> = emptyList(),
)

@Serializable
data class GhJobs(val jobs: List<GhJob> = emptyList())

@Serializable
data class GhAnnotation(
    val path: String? = null,
    val start_line: Int? = null,
    val annotation_level: String? = null,
    val title: String? = null,
    val message: String? = null,
)

@Serializable
data class GhArtifactRun(val id: Long = 0)

@Serializable
data class GhArtifact(
    val workflow_run: GhArtifactRun? = null,
    val id: Long,
    val name: String,
    val size_in_bytes: Long = 0,
    val expired: Boolean = false,
    val archive_download_url: String = "",
)

@Serializable
data class GhArtifacts(val artifacts: List<GhArtifact> = emptyList(), val total_count: Int = 0)

@Serializable
data class GhAsset(
    val name: String,
    val url: String,
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    val id: Long = 0,
    val tag_name: String,
    val created_at: String? = null,
    val name: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class DeviceCode(
    val device_code: String,
    val user_code: String,
    val verification_uri: String,
    val expires_in: Int,
    val interval: Int = 5,
)

@Serializable
data class DeviceToken(
    val access_token: String? = null,
    /** Only when the OAuth App issues expiring tokens (8 h). */
    val refresh_token: String? = null,
    val expires_in: Long? = null,
    val refresh_token_expires_in: Long? = null,
    val error: String? = null,
    val error_description: String? = null,
    val interval: Int? = null,
)

@Serializable
data class GhSecret(val name: String, val updated_at: String? = null)

@Serializable
data class GhSecrets(val secrets: List<GhSecret> = emptyList())

@Serializable
data class GhPublicKey(val key_id: String, val key: String)

@Serializable
data class GhContent(val name: String, val path: String, val type: String)

@Serializable
data class GhCompare(val ahead_by: Int = 0, val behind_by: Int = 0)

/** An upload waiting for a connection (or for the user to review a secret warning). */
@Serializable
data class QueuedUpload(
    val id: String,
    val repoId: String,
    val created: Long,
    val label: String,
    val message: String,
    val structured: Boolean,
    /** PATCH or MIRROR */
    val mode: String = "PATCH",
    val deletes: List<String> = emptyList(),
    val stripOverride: Boolean? = null,
    val excluded: List<String> = emptyList(),
    val allowedLeaks: List<String> = emptyList(),
    /** Deletions from the editor/file browser (applied in patch mode). */
    val requestedDeletes: List<String> = emptyList(),
    val origin: String = "",
    /** False when queued without a preview: only patch mode is allowed then. */
    val reviewed: Boolean = true,
    /** waiting | needs_review | failed */
    val status: String = "waiting",
    val error: String? = null,
)

@Serializable
data class GhBranch(val name: String, val commit: GhSha, val protected: Boolean = false)

@Serializable
data class GhPrRef(val ref: String, val sha: String)

@Serializable
data class GhPull(
    val number: Int,
    val title: String,
    val state: String = "open",
    val html_url: String = "",
    val head: GhPrRef,
    val base: GhPrRef,
    val mergeable: Boolean? = null,
    val mergeable_state: String? = null,
    val merged: Boolean = false,
    val body: String? = null,
)

@Serializable
data class GhCheckRun(val name: String, val status: String? = null, val conclusion: String? = null)

@Serializable
data class GhCheckRuns(val check_runs: List<GhCheckRun> = emptyList())

@Serializable
data class GhCommitAuthor(val name: String? = null, val date: String? = null)

@Serializable
data class GhCommitInfo(val message: String = "", val author: GhCommitAuthor? = null)

@Serializable
data class GhCommitFile(
    val filename: String,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    val previous_filename: String? = null,
)

@Serializable
data class GhCommitItem(
    val sha: String,
    val commit: GhCommitInfo,
    val html_url: String = "",
    val parents: List<GhSha> = emptyList(),
    val files: List<GhCommitFile> = emptyList(),
)

@Serializable
data class GhBlob(val sha: String, val content: String = "", val encoding: String = "base64", val size: Long = 0)

@Serializable
data class GhWorkflow(val id: Long, val name: String, val path: String, val state: String = "active")

@Serializable
data class GhWorkflows(val workflows: List<GhWorkflow> = emptyList())
