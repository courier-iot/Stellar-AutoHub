package com.stellar.autohub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stellar.autohub.ui.*
import com.stellar.autohub.ui.theme.StellarTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Deep link from a notification: repo id + optional run id. */
    private val openTarget = MutableStateFlow<Pair<String, Long>?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        val app = application as AutoHubApp

        setContent {
            StellarTheme {
                val nav = rememberNavController()
                var signedIn by remember { mutableStateOf(app.token() != null) }
                val target by openTarget.collectAsState()
                val shared by vm.sharedUris.collectAsState()

                LaunchedEffect(target, signedIn) {
                    val t = target ?: return@LaunchedEffect
                    if (!signedIn) return@LaunchedEffect
                    openTarget.value = null
                    if (t.second > 0) nav.navigate("run/${t.first}/${t.second}") else nav.navigate("repo/${t.first}")
                }
                LaunchedEffect(shared, signedIn) {
                    if (shared.isNotEmpty() && signedIn) nav.navigate("share") { launchSingleTop = true }
                }

                NavHost(nav, startDestination = if (signedIn) "home" else "login") {
                    composable("login") {
                        LoginScreen(onDone = {
                            signedIn = true
                            nav.navigate("home") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("home") {
                        HomeScreen(
                            openRepo = { nav.navigate("repo/$it") },
                            addRepo = { nav.navigate("addRepo") },
                            openKeys = { nav.navigate("keys") },
                            openSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("addRepo") {
                        AddRepoScreen(onBack = { nav.popBackStack() }, onAdded = { id ->
                            nav.popBackStack(); nav.navigate("repo/$id")
                        })
                    }
                    composable("repo/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        RepoScreen(
                            repoId = id, vm = vm,
                            onBack = { nav.popBackStack() },
                            openPreview = { nav.navigate("preview/$id") },
                            openRun = { run -> nav.navigate("run/$id/$run") },
                            openSecrets = { nav.navigate("secrets/$id") },
                        )
                    }
                    composable("preview/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        PreviewScreen(repoId = id, vm = vm, onBack = { vm.clearPreview(); nav.popBackStack() },
                            onCommitted = { historyId ->
                                vm.clearPreview()
                                nav.popBackStack()
                                nav.navigate("watch/$id/$historyId")
                            })
                    }
                    composable("watch/{id}/{history}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        val h = e.arguments?.getString("history") ?: return@composable
                        WatchScreen(repoId = id, historyId = h, onBack = { nav.popBackStack() },
                            openRun = { run -> nav.popBackStack(); nav.navigate("run/$id/$run") })
                    }
                    composable(
                        "run/{id}/{run}",
                        listOf(navArgument("id") { type = NavType.StringType }, navArgument("run") { type = NavType.LongType })
                    ) { e ->
                        RunScreen(
                            repoId = e.arguments?.getString("id") ?: return@composable,
                            runId = e.arguments?.getLong("run") ?: return@composable,
                            onBack = { nav.popBackStack() },
                            openSecrets = { nav.navigate("secrets/${e.arguments?.getString("id")}") },
                        )
                    }
                    composable("secrets/{id}") { e ->
                        SecretsScreen(repoId = e.arguments?.getString("id") ?: return@composable,
                            onBack = { nav.popBackStack() }, openKeys = { nav.navigate("keys") })
                    }
                    composable("keys") { KeysScreen(onBack = { nav.popBackStack() }) }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onSignedOut = {
                            signedIn = false
                            nav.navigate("login") { popUpTo(0) }
                        })
                    }
                    composable("share") {
                        ShareTargetScreen(vm = vm, onBack = { vm.clearShared(); nav.popBackStack() },
                            onPicked = { id ->
                                vm.clearShared()
                                nav.popBackStack()
                                nav.navigate("preview/$id")
                            },
                            addRepo = { nav.navigate("addRepo") })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_REPO)?.let { repo ->
            openTarget.value = repo to intent.getLongExtra(EXTRA_RUN, 0L)
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.setShared(uris)
    }

    companion object {
        const val EXTRA_REPO = "repo"
        const val EXTRA_RUN = "run"
    }
}
