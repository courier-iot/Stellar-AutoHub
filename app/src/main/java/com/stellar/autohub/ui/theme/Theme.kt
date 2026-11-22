package com.stellar.autohub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Stellar {
    val Space = Color(0xFF070A14)
    val Panel = Color(0xFF0F1428)
    val Panel2 = Color(0xFF161D38)
    val Line = Color(0xFF26305A)
    val Cyan = Color(0xFF34E7FF)
    val Violet = Color(0xFF7C4DFF)
    val Magenta = Color(0xFFFF3DCB)
    val Green = Color(0xFF3DFFA8)
    val Amber = Color(0xFFFFC14D)
    val Red = Color(0xFFFF4D6D)
    val Text = Color(0xFFE6EAFF)
    val Dim = Color(0xFF8C95C0)
}

private val scheme = darkColorScheme(
    primary = Stellar.Cyan,
    onPrimary = Color(0xFF001F26),
    secondary = Stellar.Magenta,
    onSecondary = Color(0xFF2A0020),
    tertiary = Stellar.Violet,
    background = Stellar.Space,
    onBackground = Stellar.Text,
    surface = Stellar.Panel,
    onSurface = Stellar.Text,
    surfaceVariant = Stellar.Panel2,
    onSurfaceVariant = Stellar.Dim,
    surfaceContainer = Stellar.Panel,
    surfaceContainerHigh = Stellar.Panel2,
    surfaceContainerHighest = Stellar.Panel2,
    outline = Stellar.Line,
    outlineVariant = Stellar.Line,
    error = Stellar.Red,
)

val Mono = FontFamily.Monospace

private val type = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

@Composable
fun StellarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
