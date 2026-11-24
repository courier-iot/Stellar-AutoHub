package com.stellar.autohub.upload

/** What kind of project a repo/upload is. Decides workflow templates, ignore rules and the install card. */
enum class ProjectKind(val id: String, val label: String, val platform: String) {
    ANDROID("android", "Android app", "Android"),
    ELECTRON("electron", "Electron app", "Windows"),
    NODE("node", "Node.js project", "Any"),
    DOTNET("dotnet", ".NET app", "Windows"),
    PYTHON("python", "Python project", "Any"),
    CPP("cpp", "C/C++ project", "Any"),
    UNKNOWN("", "Project", "Any");

    val isAndroid get() = this == ANDROID
    val isNodeLike get() = this == ELECTRON || this == NODE

    companion object {
        fun of(id: String?): ProjectKind = entries.firstOrNull { it.id == id && id.isNotEmpty() } ?: UNKNOWN
    }
}

object ProjectKinds {

    /**
     * Detects the kind from repo-relative paths (optionally all under one wrapping folder) and a
     * reader for small text files (used to look inside package.json).
     */
    fun detect(paths: Collection<String>, read: (String) -> String?): ProjectKind {
        // Allow one wrapping folder (zips usually have one)
        val roots = paths.map { it.substringBefore('/', "") }.toSet()
        val prefix = if (roots.size == 1 && roots.first().isNotEmpty() && paths.all { it.contains('/') }) roots.first() + "/" else ""
        val top = paths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        fun has(name: String) = name in top
        return when {
            (has("settings.gradle.kts") || has("settings.gradle")) &&
                (top.any { it.endsWith("AndroidManifest.xml") } || has("app/build.gradle.kts") || has("app/build.gradle")) -> ProjectKind.ANDROID
            has("package.json") -> {
                val pkg = read(prefix + "package.json").orEmpty()
                if (Regex("\"(electron|electron-builder|@electron-forge/cli)\"\\s*:").containsMatchIn(pkg)) ProjectKind.ELECTRON else ProjectKind.NODE
            }
            top.any { it.endsWith(".sln") || it.endsWith(".csproj") || it.endsWith(".fsproj") } -> ProjectKind.DOTNET
            has("requirements.txt") || has("pyproject.toml") || has("setup.py") -> ProjectKind.PYTHON
            has("CMakeLists.txt") -> ProjectKind.CPP
            else -> ProjectKind.UNKNOWN
        }
    }

    fun detect(payload: Payload): ProjectKind {
        val byPath = payload.files.associateBy { PathRules.normalize(it.path) ?: it.path }
        return detect(byPath.keys) { p -> byPath[p]?.let { PathRules.asText(it.bytes) } }
    }

    private val androidOnly = setOf("build/**", "*/build/**", ".gradle/**", "local.properties")

    /** Ignore patterns for this kind: the user's list, minus Android-only rules that would drop real files, plus the kind's own junk. */
    fun ignoreFor(userPatterns: String, kind: ProjectKind): List<Regex> {
        val lines = userPatterns.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toMutableList()
        if (!kind.isAndroid && kind != ProjectKind.UNKNOWN) lines.removeAll { it in androidOnly }
        lines += when (kind) {
            // electron-builder keeps app icons in build/, so build/ must stay
            ProjectKind.ELECTRON, ProjectKind.NODE -> listOf("node_modules/**", "**/node_modules/**", "dist/**", "out/**", ".npm/**", "*.log")
            ProjectKind.DOTNET -> listOf("bin/**", "obj/**", "**/bin/**", "**/obj/**", ".vs/**", "*.user")
            ProjectKind.PYTHON -> listOf("**/__pycache__/**", "*.pyc", "**/*.pyc", ".venv/**", "venv/**", "dist/**", "build/**", "*.egg-info/**")
            ProjectKind.CPP -> listOf("build/**", "cmake-build-*/**", "out/**")
            else -> emptyList()
        }
        return lines.distinct().map(PathRules::globToRegex)
    }

    /** "version" from package.json. */
    private val pkgVersion = Regex(""""version"\s*:\s*"([^"]+)"""")

    fun packageJsonVersion(text: String): String? = pkgVersion.find(text)?.groupValues?.get(1)

    fun bumpPackageJson(text: String, newVersion: String): String {
        val m = pkgVersion.find(text) ?: return text
        return text.replaceRange(m.groups[1]!!.range, newVersion)
    }

    /** Which file holds the version for a kind (repo-relative). */
    fun versionFiles(kind: ProjectKind): List<String> = when {
        kind.isNodeLike -> listOf("package.json")
        else -> PathRules.GRADLE_FILES + "package.json"
    }
}
