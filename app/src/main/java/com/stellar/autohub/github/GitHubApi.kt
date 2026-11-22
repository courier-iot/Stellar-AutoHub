package com.stellar.autohub.github

import com.stellar.autohub.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GitHubException(val code: Int, message: String) : IOException(message)

/**
 * Thin GitHub REST client. Every call is suspend + runs on IO.
 * OkHttp drops the Authorization header on cross-host redirects, so log and
 * artifact downloads (which redirect to blob storage) are safe.
 */
class GitHubApi(private val tokenProvider: () -> String?) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Scopes reported by GitHub for classic/OAuth tokens (empty for fine-grained). */
    @Volatile var lastScopes: String? = null

    private fun req(url: String, accept: String = "application/vnd.github+json"): Request.Builder {
        val b = Request.Builder()
            .url(if (url.startsWith("http")) url else "https://api.github.com$url")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Stellar-AutoHub")
        tokenProvider()?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    /** Executes with retry for secondary rate limits / transient 5xx. */
    private suspend fun exec(request: Request): Response = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val resp = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                if (attempt++ < 3) { delay(2000L * attempt); continue } else throw e
            }
            resp.header("X-OAuth-Scopes")?.let { lastScopes = it }
            val code = resp.code
            val limited = code == 429 || (code == 403 && (resp.header("Retry-After") != null ||
                resp.header("X-RateLimit-Remaining") == "0"))
            if ((limited || code in 502..504) && attempt < 5) {
                val wait = resp.header("Retry-After")?.toLongOrNull()?.times(1000)
                    ?: resp.header("X-RateLimit-Reset")?.toLongOrNull()
                        ?.let { (it * 1000 - System.currentTimeMillis()).coerceIn(1000, 90_000) }
                    ?: (5000L * (attempt + 1))
                resp.close()
                attempt++
                delay(wait.coerceAtMost(90_000))
                continue
            }
            return@withContext resp
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    private suspend fun text(request: Request): String {
        exec(request).use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw GitHubException(r.code, errorMessage(r.code, body))
            return body
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = try {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        } catch (e: Exception) { null } ?: body.take(200)
        return when (code) {
            401 -> "GitHub says the token is invalid or expired ($msg). Sign in again."
            403 -> "Permission denied: $msg"
            404 -> "Not found (or no access): $msg"
            409 -> "Conflict: $msg"
            422 -> "GitHub rejected the request: $msg"
            else -> "GitHub error $code: $msg"
        }
    }

    private suspend fun <T> get(url: String, s: DeserializationStrategy<T>): T =
        json.decodeFromString(s, text(req(url).get().build()))

    private suspend fun <T> send(method: String, url: String, body: JsonElement, s: DeserializationStrategy<T>): T =
        json.decodeFromString(s, text(req(url).method(method, body.toString().toRequestBody(jsonType)).build()))

    private fun enc(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ---------------- Auth ----------------

    suspend fun requestDeviceCode(clientId: String): DeviceCode {
        val r = Request.Builder().url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", clientId).add("scope", "repo workflow").build())
            .build()
        val body = text(r)
        val obj = json.parseToJsonElement(body).jsonObject
        obj["error"]?.let { throw GitHubException(400, obj["error_description"]?.jsonPrimitive?.content ?: it.jsonPrimitive.content) }
        return json.decodeFromString(DeviceCode.serializer(), body)
    }

    suspend fun pollDeviceToken(clientId: String, deviceCode: String): DeviceToken {
        val r = Request.Builder().url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .post(
                FormBody.Builder().add("client_id", clientId).add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
            ).build()
        return json.decodeFromString(DeviceToken.serializer(), text(r))
    }

    suspend fun user(): GhUser = get("/user", GhUser.serializer())

    // ---------------- Repos ----------------

    suspend fun myRepos(): List<GhRepo> {
        val all = mutableListOf<GhRepo>()
        for (page in 1..5) {
            val list = get("/user/repos?per_page=100&sort=pushed&page=$page", ListSerializer(GhRepo.serializer()))
            all += list
            if (list.size < 100) break
        }
        return all
    }

    suspend fun repo(owner: String, name: String): GhRepo = get("/repos/$owner/$name", GhRepo.serializer())

    /** Raw file text, or null if it doesn't exist. */
    suspend fun rawFile(owner: String, name: String, path: String, ref: String): String? = try {
        text(req("/repos/$owner/$name/contents/${enc(path)}?ref=${enc(ref)}", "application/vnd.github.raw+json").get().build())
    } catch (e: GitHubException) {
        if (e.code == 404) null else throw e
    }

    // ---------------- Git data ----------------

    /** Head commit sha of the branch, or null if the repo is empty / branch missing. */
    suspend fun branchHead(owner: String, name: String, branch: String): String? = try {
        get("/repos/$owner/$name/git/ref/heads/${enc(branch)}", GhRef.serializer()).`object`.sha
    } catch (e: GitHubException) {
        if (e.code == 404 || e.code == 409) null else throw e
    }

    suspend fun commit(owner: String, name: String, sha: String): GhCommit =
        get("/repos/$owner/$name/git/commits/$sha", GhCommit.serializer())

    suspend fun tree(owner: String, name: String, sha: String): GhTree =
        get("/repos/$owner/$name/git/trees/$sha?recursive=1", GhTree.serializer())

    suspend fun createBlob(owner: String, name: String, base64: String): String =
        send("POST", "/repos/$owner/$name/git/blobs",
            buildJsonObject { put("content", base64); put("encoding", "base64") }, GhSha.serializer()).sha

    suspend fun createTree(owner: String, name: String, baseTree: String?, entries: JsonArray): String =
        send("POST", "/repos/$owner/$name/git/trees", buildJsonObject {
            if (baseTree != null) put("base_tree", baseTree)
            put("tree", entries)
        }, GhSha.serializer()).sha

    suspend fun createCommit(owner: String, name: String, message: String, tree: String, parent: String?): String =
        send("POST", "/repos/$owner/$name/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            putJsonArray("parents") { if (parent != null) add(parent) }
        }, GhSha.serializer()).sha

    suspend fun updateRef(owner: String, name: String, branch: String, sha: String) {
        send("PATCH", "/repos/$owner/$name/git/refs/heads/${enc(branch)}", buildJsonObject {
            put("sha", sha); put("force", false)
        }, JsonObject.serializer())
    }

    /** Used only to bootstrap an empty repo (Git Data API refuses empty repos). */
    suspend fun putContents(owner: String, name: String, path: String, base64: String, branch: String, message: String) {
        send("PUT", "/repos/$owner/$name/contents/${enc(path)}", buildJsonObject {
            put("message", message); put("content", base64); put("branch", branch)
        }, JsonObject.serializer())
    }

    // ---------------- Actions ----------------

    suspend fun runsForSha(owner: String, name: String, sha: String): List<GhRun> =
        get("/repos/$owner/$name/actions/runs?head_sha=$sha&per_page=20", GhRuns.serializer()).workflow_runs

    suspend fun recentRuns(owner: String, name: String, branch: String): List<GhRun> =
        get("/repos/$owner/$name/actions/runs?branch=${enc(branch)}&per_page=15", GhRuns.serializer()).workflow_runs

    suspend fun run(owner: String, name: String, id: Long): GhRun =
        get("/repos/$owner/$name/actions/runs/$id", GhRun.serializer())

    suspend fun jobs(owner: String, name: String, runId: Long): List<GhJob> =
        get("/repos/$owner/$name/actions/runs/$runId/jobs?per_page=50", GhJobs.serializer()).jobs

    suspend fun annotations(owner: String, name: String, jobId: Long): List<GhAnnotation> = try {
        get("/repos/$owner/$name/check-runs/$jobId/annotations?per_page=50", ListSerializer(GhAnnotation.serializer()))
    } catch (e: GitHubException) { emptyList() }

    suspend fun jobLog(owner: String, name: String, jobId: Long): String =
        text(req("/repos/$owner/$name/actions/jobs/$jobId/logs", "application/vnd.github+json").get().build())

    suspend fun rerunFailed(owner: String, name: String, runId: Long) {
        exec(req("/repos/$owner/$name/actions/runs/$runId/rerun-failed-jobs").post(ByteArray(0).toRequestBody()).build()).use {
            if (!it.isSuccessful) throw GitHubException(it.code, errorMessage(it.code, it.body?.string() ?: ""))
        }
    }

    suspend fun rerunAll(owner: String, name: String, runId: Long) {
        exec(req("/repos/$owner/$name/actions/runs/$runId/rerun").post(ByteArray(0).toRequestBody()).build()).use {
            if (!it.isSuccessful) throw GitHubException(it.code, errorMessage(it.code, it.body?.string() ?: ""))
        }
    }

    suspend fun artifacts(owner: String, name: String, runId: Long): List<GhArtifact> =
        get("/repos/$owner/$name/actions/runs/$runId/artifacts", GhArtifacts.serializer()).artifacts

    // ---------------- Releases ----------------

    suspend fun latestRelease(owner: String, name: String): GhRelease? = try {
        get("/repos/$owner/$name/releases/latest", GhRelease.serializer())
    } catch (e: GitHubException) { if (e.code == 404) null else throw e }

    suspend fun releaseByTag(owner: String, name: String, tag: String): GhRelease? = try {
        get("/repos/$owner/$name/releases/tags/${enc(tag)}", GhRelease.serializer())
    } catch (e: GitHubException) { if (e.code == 404) null else throw e }

    // ---------------- Actions secrets ----------------

    suspend fun secrets(owner: String, name: String): List<GhSecret> =
        get("/repos/$owner/$name/actions/secrets?per_page=100", GhSecrets.serializer()).secrets

    suspend fun secretsPublicKey(owner: String, name: String): GhPublicKey =
        get("/repos/$owner/$name/actions/secrets/public-key", GhPublicKey.serializer())

    suspend fun putSecret(owner: String, name: String, secret: String, encryptedB64: String, keyId: String) {
        exec(req("/repos/$owner/$name/actions/secrets/${enc(secret)}").put(buildJsonObject {
            put("encrypted_value", encryptedB64); put("key_id", keyId)
        }.toString().toRequestBody(jsonType)).build()).use {
            if (!it.isSuccessful) throw GitHubException(it.code, errorMessage(it.code, it.body?.string() ?: ""))
        }
    }

    suspend fun deleteSecret(owner: String, name: String, secret: String) {
        exec(req("/repos/$owner/$name/actions/secrets/${enc(secret)}").delete().build()).use {
            if (!it.isSuccessful && it.code != 404) throw GitHubException(it.code, errorMessage(it.code, it.body?.string() ?: ""))
        }
    }

    /** Paths of workflow files on the branch. */
    suspend fun workflowFiles(owner: String, name: String, ref: String): List<String> = try {
        get("/repos/$owner/$name/contents/.github/workflows?ref=${enc(ref)}", ListSerializer(GhContent.serializer()))
            .filter { it.type == "file" && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .map { it.path }
    } catch (e: GitHubException) { if (e.code == 404) emptyList() else throw e }

    // ---------------- Downloads ----------------

    suspend fun download(url: String, accept: String, dest: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            exec(req(url, accept).get().build()).use { r ->
                if (!r.isSuccessful) throw GitHubException(r.code, errorMessage(r.code, r.body?.string() ?: ""))
                val body = r.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        }
}
