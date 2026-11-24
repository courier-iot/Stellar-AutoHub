package com.stellar.autohub.upload

import com.stellar.autohub.data.GhTreeEntry

enum class FileStatus { ADDED, CHANGED, UNCHANGED }
enum class UploadMode { PATCH, MIRROR }

class PlannedFile(
    val path: String,
    val bytes: ByteArray,
    val status: FileStatus,
    val mode: String,
)

class PlannedDelete(val path: String, val mode: String, val protected: Boolean)

class Skipped(val path: String, val reason: String)

fun Leak.key() = "$path:$line:$kind"

class Plan(
    val files: List<PlannedFile>,
    /** Repo files missing from the upload. Only removed in MIRROR mode, and only the selected ones. */
    val deletable: List<PlannedDelete>,
    val skipped: List<Skipped>,
    val strippedRoot: String?,
    val canStrip: String?,
    val looksLikeProject: Boolean,
    val version: String?,
    val applicationId: String?,
    val warnings: List<String>,
    val remoteHead: String?,
    val remoteTreeSha: String?,
    val leaks: List<Leak> = emptyList(),
    /** Path of the gradle file holding versionName (repo path, after strip). */
    val gradlePath: String? = null,
    /** Deletions asked for explicitly (editor/file browser); applied in patch mode too. */
    val requestedDeletes: Set<String> = emptySet(),
    /** Pre-commit problems (unbalanced brackets, invalid YAML/JSON/XML) in new/changed files. */
    val checks: List<com.stellar.autohub.editor.CheckIssue> = emptyList(),
    val kind: ProjectKind = ProjectKind.UNKNOWN,
) {
    /** Same plan without some files (e.g. ones the user excluded after a secret warning). */
    fun excluding(paths: Set<String>): Plan = if (paths.isEmpty()) this else Plan(
        files.filter { it.path !in paths }, deletable, skipped, strippedRoot, canStrip, looksLikeProject, version,
        applicationId, warnings, remoteHead, remoteTreeSha, leaks.filter { it.path !in paths }, gradlePath, requestedDeletes,
        checks.filter { it.path !in paths }, kind,
    )

    val added get() = files.count { it.status == FileStatus.ADDED }
    val changed get() = files.count { it.status == FileStatus.CHANGED }
    val unchanged get() = files.count { it.status == FileStatus.UNCHANGED }
}

class RemoteState(
    val head: String?,
    val treeSha: String?,
    val entries: List<GhTreeEntry>,
    val truncated: Boolean,
)

object Planner {

    private val projectMarkers = listOf(
        "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle",
        "package.json", "pubspec.yaml", "Cargo.toml", "pom.xml",
    )

    /**
     * @param strip null = decide automatically, true/false = user override.
     */
    fun plan(
        payload: Payload,
        remote: RemoteState,
        ignore: List<Regex>,
        protectedPatterns: List<Regex>,
        strip: Boolean?,
        autoStrip: Boolean,
    ): Plan {
        val skipped = mutableListOf<Skipped>()
        val warnings = mutableListOf<String>()

        // 1. Normalize paths
        var files = payload.files.mapNotNull { f ->
            val p = PathRules.normalize(f.path)
            if (p == null) { skipped += Skipped(f.path, "unsafe path"); null } else RawFile(p, f.bytes)
        }

        val remoteFiles = remote.entries.filter { it.type == "blob" }.associateBy { it.path }
        val remoteTopLevel = remote.entries.map { it.path.substringBefore('/') }.toSet()

        // 2. Detect a single wrapping folder (e.g. "MyApp/...") that zips usually have
        val junkFree = files.filterNot { it.path.startsWith("__MACOSX/") }
        val roots = junkFree.map { it.path.substringBefore('/', "") }.toSet()
        val canStrip = if (payload.structured && roots.size == 1 && roots.first().isNotEmpty() &&
            junkFree.all { it.path.contains('/') }) roots.first() else null
        val doStrip = when {
            canStrip == null -> false
            strip != null -> strip
            !autoStrip -> false
            // Don't strip if the repo really has that folder at its root (e.g. "app")
            canStrip in remoteTopLevel -> false
            else -> true
        }
        if (doStrip && canStrip != null) {
            files = files.mapNotNull { f ->
                if (f.path.startsWith("$canStrip/")) RawFile(f.path.removePrefix("$canStrip/"), f.bytes)
                else { skipped += Skipped(f.path, "outside project folder"); null }
            }
        }

        // 3. Ignore rules + duplicates (last one wins)
        val byPath = LinkedHashMap<String, RawFile>()
        for (f in files) {
            if (PathRules.matchesAny(f.path, ignore)) {
                val reason = if (f.path.endsWith(".jks", true) || f.path.endsWith(".keystore", true))
                    "signing key, kept out of the repo" else "ignored"
                skipped += Skipped(f.path, reason)
            } else {
                byPath[f.path] = f
            }
        }

        // 4. Compare with what's in the repo
        val planned = byPath.values.map { f ->
            val remoteEntry = remoteFiles[f.path]
            val status = when {
                remoteEntry == null -> FileStatus.ADDED
                remoteEntry.sha == PathRules.gitBlobSha(f.bytes) -> FileStatus.UNCHANGED
                else -> FileStatus.CHANGED
            }
            PlannedFile(f.path, f.bytes, status, remoteEntry?.mode?.takeIf { it.startsWith("100") } ?: PathRules.modeFor(f.path))
        }.sortedBy { it.path }

        val uploaded = byPath.keys
        val deletable = remoteFiles.values
            .filter { it.path !in uploaded && !PathRules.matchesAny(it.path, ignore) }
            .map { PlannedDelete(it.path, it.mode, PathRules.matchesAny(it.path, protectedPatterns)) }
            .sortedBy { it.path }

        val looksLikeProject = payload.structured && uploaded.any { it in projectMarkers || it.startsWith(".github/") }

        // 5. Detect version / app id from the uploaded gradle file (Android) or package.json (Electron/Node)
        val gradle = PathRules.GRADLE_FILES.firstNotNullOfOrNull { byPath[it] }?.let { String(it.bytes) }
        val pkgJson = byPath["package.json"]?.let { String(it.bytes) }
        val gradleVersion = gradle?.let { PathRules.versionName(it) }
        val version = gradleVersion ?: pkgJson?.let { ProjectKinds.packageJsonVersion(it) }
        val versionPath = when {
            gradleVersion != null -> PathRules.GRADLE_FILES.firstOrNull { byPath.containsKey(it) }
            version != null -> "package.json"
            else -> null
        }
        val appId = gradle?.let { PathRules.applicationId(it) }
        val kind = ProjectKinds.detect(uploaded) { p -> byPath[p]?.let { PathRules.asText(it.bytes) } }
            .let { if (it == ProjectKind.UNKNOWN) ProjectKinds.detect(remoteFiles.keys) { null } else it }

        // 6. Warnings
        if (remote.truncated) warnings += "This repo is huge, so GitHub sent a partial file list. Deletions are turned off for safety."
        if (canStrip != null && !doStrip) warnings += "Everything is inside \"$canStrip/\". If that's the project folder, turn on Strip top folder."
        val hasWorkflow = uploaded.any { it.startsWith(".github/workflows/") } ||
            remoteFiles.keys.any { it.startsWith(".github/workflows/") }
        if (!hasWorkflow) warnings += "No .github/workflows found in the upload or the repo, so nothing will build." +
            " (Repo screen → Workflow health can add one.)"
        if (planned.any { it.path.startsWith(".github/workflows/") && it.status != FileStatus.UNCHANGED })
            warnings += "Workflow files change in this upload. Your token needs the 'workflow' permission."
        if (payload.structured && !looksLikeProject && planned.isNotEmpty())
            warnings += "This doesn't look like a full project root. Check the paths before committing."

        return Plan(
            files = planned,
            deletable = if (remote.truncated) emptyList() else deletable,
            skipped = skipped,
            strippedRoot = if (doStrip) canStrip else null,
            canStrip = canStrip,
            looksLikeProject = looksLikeProject,
            version = version,
            applicationId = appId,
            warnings = warnings,
            remoteHead = remote.head,
            remoteTreeSha = remote.treeSha,
            leaks = LeakScanner.scan(planned),
            gradlePath = versionPath,
            kind = kind,
            checks = planned.filter { it.status != FileStatus.UNCHANGED }.flatMap { f ->
                PathRules.asText(f.bytes)?.let { com.stellar.autohub.editor.Checks.check(f.path, it) } ?: emptyList()
            },
            requestedDeletes = payload.deletes.mapNotNull { PathRules.normalize(it) }.filter { it in remoteFiles && it !in uploaded }.toSet(),
        )
    }
}
