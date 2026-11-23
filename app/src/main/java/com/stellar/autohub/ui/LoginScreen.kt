package com.stellar.autohub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.autohub.AutoHubApp
import com.stellar.autohub.ui.theme.Mono
import com.stellar.autohub.ui.theme.Stellar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val app = AutoHubApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(app.prefs.clientId) }
    var userCode by remember { mutableStateOf<String?>(null) }
    var verifyUrl by remember { mutableStateOf("https://github.com/login/device") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(app.prefs.clientId.isBlank()) }

    suspend fun finish(t: String, refresh: String? = null, expiresIn: Long? = null) {
        app.setToken(t, refresh, expiresIn)
        val u = app.api.user()
        app.prefs.login = u.login
        onDone()
    }

    Screen(title = "Stellar AutoHub") { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connect to GitHub", style = MaterialTheme.typography.headlineSmall)
            Text("AutoHub needs access to your repos (code + workflows + secrets) to upload, build and manage them.", color = Stellar.Dim)

            error?.let { ErrorBox(it) }
            val online by app.online.collectAsState()
            if (!online) InfoBox("No internet connection right now. AutoHub will reconnect automatically.")

            Panel(accent = Stellar.Cyan) {
                Label("Sign in with a code")
                if (userCode == null) {
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it.trim() },
                        label = { Text("OAuth App Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !busy && clientId.isNotBlank(),
                        onClick = {
                            busy = true; error = null
                            app.prefs.clientId = clientId
                            scope.launch {
                                try {
                                    val dc = app.api.requestDeviceCode(clientId)
                                    userCode = dc.user_code
                                    verifyUrl = dc.verification_uri
                                    var interval = dc.interval.coerceAtLeast(5)
                                    val until = System.currentTimeMillis() + dc.expires_in * 1000L
                                    while (System.currentTimeMillis() < until) {
                                        delay(interval * 1000L)
                                        val t = try {
                                            app.api.pollDeviceToken(clientId, dc.device_code)
                                        } catch (e: java.io.IOException) {
                                            continue // connection blip (e.g. VPN switch): keep waiting for approval
                                        }
                                        when {
                                            t.access_token != null -> { finish(t.access_token, t.refresh_token, t.expires_in); return@launch }
                                            t.error == "authorization_pending" -> {}
                                            t.error == "slow_down" -> interval = (t.interval ?: (interval + 5))
                                            else -> throw IllegalStateException(t.error_description ?: t.error ?: "Sign-in failed")
                                        }
                                    }
                                    throw IllegalStateException("The code expired. Try again.")
                                } catch (e: Exception) {
                                    error = e.message
                                    userCode = null
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in with GitHub") }
                    if (busy) Busy("Contacting GitHub…")
                    TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide setup steps" else "Where do I get a Client ID?") }
                    if (showHelp) {
                        Text(
                            "One-time setup (2 min):\n" +
                                "1. github.com → Settings → Developer settings → OAuth Apps → New OAuth App\n" +
                                "2. Name: Stellar AutoHub · Homepage + Callback URL: https://github.com\n" +
                                "3. Register, then tick \"Enable Device Flow\" and save\n" +
                                "4. Copy the Client ID here. No secret needed.",
                            color = Stellar.Dim, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text("Enter this code on GitHub:", color = Stellar.Dim)
                    Text(
                        userCode!!, fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = Stellar.Cyan, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                    Button(onClick = {
                        ctx.copy("GitHub code", userCode!!)
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Copy code & open GitHub") }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Waiting for you to approve…", color = Stellar.Dim)
                    }
                }
            }

            Panel {
                Label("Or paste a token", Stellar.Magenta)
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() },
                    label = { Text("Personal access token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Classic token: scopes repo + workflow.\nFine-grained: Contents, Actions, Workflows and Secrets set to Read & write (Metadata: read).",
                    color = Stellar.Dim, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    enabled = !busy && token.length > 10,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try { finish(token) } catch (e: Exception) { app.setToken(null); error = e.message } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use token") }
            }
        }
    }
}
