package com.stellar.autohub.secrets

import android.util.Base64
import com.stellar.autohub.data.RepoProfile
import com.stellar.autohub.github.GitHubApi

enum class SigningRole(val label: String) {
    KEYSTORE("Key file (base64)"),
    STORE_PASSWORD("Store password"),
    KEY_ALIAS("Key alias"),
    KEY_PASSWORD("Key password"),
    NONE("Not a signing secret"),
}

/** A secret name a workflow uses, and whether the repo has it. */
data class SecretNeed(
    val name: String,
    val isSet: Boolean,
    val usedIn: List<String>,
    val role: SigningRole,
    val updatedAt: String? = null,
)

data class SecretsOverview(
    val needs: List<SecretNeed>,
    /** Secrets that exist in the repo but no workflow mentions. */
    val unused: List<SecretNeed>,
) {
    val missing get() = needs.filter { !it.isSet }
}

class SecretsManager(private val api: GitHubApi) {

    private val ref = Regex("""secrets\.([A-Za-z_][A-Za-z0-9_]*)""")
    private val builtIn = setOf("GITHUB_TOKEN")

    /** Reads every workflow file and finds `${{ secrets.NAME }}` references. */
    suspend fun overview(repo: RepoProfile): SecretsOverview {
        val existing = api.secrets(repo.owner, repo.name).associateBy { it.name.uppercase() }
        val used = linkedMapOf<String, MutableList<String>>()
        for (path in api.workflowFiles(repo.owner, repo.name, repo.branch)) {
            val text = api.rawFile(repo.owner, repo.name, path, repo.branch) ?: continue
            for (m in ref.findAll(text)) {
                val n = m.groupValues[1].uppercase()
                if (n in builtIn) continue
                used.getOrPut(n) { mutableListOf() }.let { if (path.substringAfterLast('/') !in it) it += path.substringAfterLast('/') }
            }
        }
        val needs = used.map { (n, files) ->
            SecretNeed(n, existing.containsKey(n), files, guessRole(n), existing[n]?.updated_at)
        }
        val unused = existing.values.filter { it.name.uppercase() !in used.keys }
            .map { SecretNeed(it.name, true, emptyList(), guessRole(it.name), it.updated_at) }
        return SecretsOverview(needs, unused)
    }

    suspend fun put(repo: RepoProfile, name: String, value: String) {
        val pk = api.secretsPublicKey(repo.owner, repo.name)
        val sealed = SealedBox.seal(value.toByteArray(), Base64.decode(pk.key, Base64.DEFAULT))
        api.putSecret(repo.owner, repo.name, name, Base64.encodeToString(sealed, Base64.NO_WRAP), pk.key_id)
    }

    suspend fun delete(repo: RepoProfile, name: String) = api.deleteSecret(repo.owner, repo.name, name)

    /** Values for each role from a vault key. */
    fun valueFor(role: SigningRole, key: SigningKey): String? = when (role) {
        SigningRole.KEYSTORE -> key.keystoreBase64
        SigningRole.STORE_PASSWORD -> key.password
        SigningRole.KEY_ALIAS -> key.alias
        SigningRole.KEY_PASSWORD -> key.effectiveKeyPassword
        SigningRole.NONE -> null
    }

    /**
     * Which secret names to fill for signing. Uses the workflow's own names when it has
     * signing-looking ones, otherwise the standard Stellar names.
     */
    fun signingMapping(overview: SecretsOverview?): Map<String, SigningRole> {
        val fromWorkflow = overview?.needs?.filter { it.role != SigningRole.NONE }?.associate { it.name to it.role }
        if (!fromWorkflow.isNullOrEmpty()) return fromWorkflow
        return linkedMapOf(
            "KEYSTORE_BASE64" to SigningRole.KEYSTORE,
            "KEYSTORE_PASSWORD" to SigningRole.STORE_PASSWORD,
            "KEY_ALIAS" to SigningRole.KEY_ALIAS,
            "KEY_PASSWORD" to SigningRole.KEY_PASSWORD,
        )
    }

    companion object {
        fun guessRole(raw: String): SigningRole {
            val n = raw.uppercase()
            val keyish = listOf("KEYSTORE", "KEY_STORE", "JKS", "SIGNING", "SIGN_KEY", "RELEASE_KEY", "UPLOAD_KEY").any { it in n }
            return when {
                "ALIAS" in n -> SigningRole.KEY_ALIAS
                ("PASS" in n || "PWD" in n) && ("STORE" in n || "JKS" in n) -> SigningRole.STORE_PASSWORD
                ("PASS" in n || "PWD" in n) && "KEY" in n -> SigningRole.KEY_PASSWORD
                ("PASS" in n || "PWD" in n) && "SIGN" in n -> SigningRole.STORE_PASSWORD
                keyish && ("BASE64" in n || "B64" in n || "FILE" in n || "KEYSTORE" == n || n.endsWith("KEYSTORE") || "JKS" in n) -> SigningRole.KEYSTORE
                n == "SIGNING_KEY" || n == "SIGNING_KEY_BASE64" -> SigningRole.KEYSTORE
                else -> SigningRole.NONE
            }
        }
    }
}
