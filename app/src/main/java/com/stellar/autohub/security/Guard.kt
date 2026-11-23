package com.stellar.autohub.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.stellar.autohub.AutoHubApp

/** Fingerprint / screen-lock checks for opening the app and for sensitive actions. */
object Guard {
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun Context.activity(): FragmentActivity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** True when the phone has a fingerprint, face or screen lock set up. */
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(ctx: Context, title: String, subtitle: String? = null, onFail: () -> Unit = {}, onOk: () -> Unit) {
        val act = ctx.activity()
        if (act == null || !available(ctx)) { onOk(); return }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON && code != BiometricPrompt.ERROR_CANCELED)
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
                onFail()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTH)
            .build()
        prompt.authenticate(info)
    }

    /** Runs [action] after a fingerprint/PIN check, if "confirm sensitive actions" is on. */
    fun sensitive(ctx: Context, title: String, action: () -> Unit) {
        if (!AutoHubApp.instance.prefs.confirmSensitive) action() else prompt(ctx, title, "Confirm it's you", onOk = action)
    }
}

/** Blocks screenshots and the recent-apps preview while this screen is shown. */
@Composable
fun SecureScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val act: Activity? = with(Guard) { ctx.activity() }
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
