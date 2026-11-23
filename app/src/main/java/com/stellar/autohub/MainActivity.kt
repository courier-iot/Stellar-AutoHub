package com.stellar.autohub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stellar.autohub.ui.*
import com.stellar.autohub.ui.theme.StellarTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Deep link from a notification: repo id + optional run id. */
    private val openTarget = MutableStateFlow<Pair<String, Long>?>(null)

    private val locked = MutableStateFlow(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        val app = application as AutoHubApp
        locked.value = app.prefs.appLock && !app.unlocked

        setContent {
            StellarTheme {
                val nav = rememberNavController()
                var signedIn by remember { mutableStateOf(app.hasToken()) }
                val target by openTarget.collectAsState()
                val shared by vm.sharedUris.collectAsState()
                val isLocked by locked.collectAsState()

                // Token health: a quick check on start flips authProblem if GitHub rejects the token
                LaunchedEffect(signedIn) { if (signedIn) try { app.api.user() } catch (_: Exception) { } }


                LaunchedEffect(target, signedIn) {
                    val t = target ?: return@LaunchedEffect
                    if (!signedIn) return@LaunchedEffect
                    openTarget.value = null
                    if (t.second > 0) nav.navigate("run/${t.first}/${t.second}") else nav.navigate("repo/${t.first}")
                }
                LaunchedEffect(shared, signedIn) {
                    if (shared.isNotEmpty() && signedIn) nav.navigate("share") { launchSingleTop = true }
                }

                Box(Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = if (signedIn) "home" else "login") {
                    composable("login") {
                        LoginScreen(onDone = {
                            signedIn = true
                            nav.navigate("home") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("home") {
                        HomeScreen(
                            reLogin = {
                                app.setToken(null); signedIn = false
                                nav.navigate("login") { popUpTo(0) }
                            },
                            openCrashes = { nav.navigate("crashes") },
                            openBackup = { nav.navigate("backup") },
                            openRepo = { nav.navigate("repo/$it") },
                            addRepo = { nav.navigate("addRepo") },
                            openKeys = { nav.navigate("keys") },
                            openSettings = { nav.navigate("settings") },
                            newProject = { nav.navigate("newProject") },
                        )
                    }
                    composable("newProject") {
                        NewProjectScreen(onBack = { nav.popBackStack() }, onCreated = { id -> nav.popBackStack(); nav.navigate("repo/$id") })
                    }
                    composable("files/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        FileBrowserScreen(id, onBack = { nav.popBackStack() }, openFile = { p -> nav.openEditor(id, p) },
                            openSearch = { nav.navigate("search/$id") }, openChanges = { nav.navigate("changes/$id") })
                    }
                    composable(
                        "edit/{id}?path={path}&line={line}&src={src}",
                        listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("path") { type = NavType.StringType; defaultValue = "" },
                            navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                            navArgument("src") { type = NavType.StringType; defaultValue = "repo" },
                        )
                    ) { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        EditorScreen(
                            repoId = id,
                            path = e.arguments?.getString("path").orEmpty(),
                            line = e.arguments?.getInt("line") ?: 0,
                            source = if (e.arguments?.getString("src") == "upload") com.stellar.autohub.editor.EditorSession.Source.UPLOAD
                                else com.stellar.autohub.editor.EditorSession.Source.REPO,
                            vm = vm,
                            onBack = { nav.popBackStack() },
                            openChanges = { nav.navigate("changes/$id") },
                            openDiff = { nav.navigate("diff") },
                        )
                    }
                    composable("diff") { DiffScreen(onBack = { nav.popBackStack() }) }
                    composable("changes/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        ChangesScreen(id, vm, onBack = { nav.popBackStack() }, openFile = { p -> nav.openEditor(id, p) },
                            openDiff = { nav.navigate("diff") }, openPreview = { nav.navigate("preview/$id") })
                    }
                    composable("history/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        HistoryScreen(id, onBack = { nav.popBackStack() }, openCommit = { sha -> nav.navigate("commit/$id/$sha") })
                    }
                    composable("commit/{id}/{sha}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        CommitScreen(id, e.arguments?.getString("sha") ?: return@composable, onBack = { nav.popBackStack() },
                            openChanges = { nav.navigate("changes/$id") })
                    }
                    composable("search/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        SearchScreen(id, onBack = { nav.popBackStack() }, openFile = { p, l -> nav.openEditor(id, p, l) })
                    }
                    composable("branches/{id}") { e ->
                        BranchesScreen(e.arguments?.getString("id") ?: return@composable, onBack = { nav.popBackStack() })
                    }
                    composable("stats/{id}") { e ->
                        StatsScreen(e.arguments?.getString("id") ?: return@composable, onBack = { nav.popBackStack() })
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
                            openCleanup = { nav.navigate("cleanup/$id") },
                            openFiles = { nav.navigate("files/$id") },
                            openChanges = { nav.navigate("changes/$id") },
                            openHistory = { nav.navigate("history/$id") },
                            openSearch = { nav.navigate("search/$id") },
                            openBranches = { nav.navigate("branches/$id") },
                            openStats = { nav.navigate("stats/$id") },
                        )
                    }
                    composable("preview/{id}") { e ->
                        val id = e.arguments?.getString("id") ?: return@composable
                        PreviewScreen(repoId = id, vm = vm, onBack = { vm.clearPreview(); nav.popBackStack() },
                            onCommitted = { historyId ->
                                vm.clearPreview()
                                nav.popBackStack()
                                nav.navigate("watch/$id/$historyId")
                            },
                            openEditor = { p -> nav.openEditor(id, p, src = "upload") },
                            openDiff = { nav.navigate("diff") })
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
                            openEditor = { p, l -> nav.openEditor(e.arguments?.getString("id") ?: "", p, l) },
                        )
                    }
                    composable("secrets/{id}") { e ->
                        SecretsScreen(repoId = e.arguments?.getString("id") ?: return@composable,
                            onBack = { nav.popBackStack() }, openKeys = { nav.navigate("keys") })
                    }
                    composable("keys") { KeysScreen(onBack = { nav.popBackStack() }) }
                    composable("crashes") { CrashScreen(onBack = { nav.popBackStack() }) }
                    composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
                    composable("cleanup/{id}") { e ->
                        CleanupScreen(repoId = e.arguments?.getString("id") ?: return@composable, onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, openCrashes = { nav.navigate("crashes") },
                            openBackup = { nav.navigate("backup") }, onSignedOut = {
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
                if (isLocked) LockScreen(onUnlocked = { app.unlocked = true; locked.value = false })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as AutoHubApp
        val away = System.currentTimeMillis() - app.backgroundedAt
        if (app.prefs.appLock && app.backgroundedAt > 0 && away > app.prefs.lockAfterSec * 1000L) {
            app.unlocked = false
            locked.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        (application as AutoHubApp).backgroundedAt = System.currentTimeMillis()
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

/** Opens a file in the editor (paths are URL-encoded so slashes survive navigation). */
fun androidx.navigation.NavController.openEditor(repoId: String, path: String, line: Int = 0, src: String = "repo") =
    navigate("edit/$repoId?path=${Uri.encode(path)}&line=$line&src=$src")
