package com.stellar.autohub.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

data class Palette(
    val space: Color = Color(0xFF070A14),
    val panel: Color = Color(0xFF0F1428),
    val panel2: Color = Color(0xFF161D38),
    val line: Color = Color(0xFF2A3566),
    val accent: Color = Color(0xFF34E7FF),
    val text: Color = Color(0xFFE9ECFF),
    val dim: Color = Color(0xFFA3ACD6),
    val textScale: Float = 1f,
    val pureBlack: Boolean = false,
)

/**
 * App colors. Backed by Compose state, so changing display settings recolors everything live.
 * "Cyan" is the accent slot (it follows the accent setting).
 */
object Stellar {
    var palette by mutableStateOf(Palette())

    val Space get() = palette.space
    val Panel get() = palette.panel
    val Panel2 get() = palette.panel2
    val Line get() = palette.line
    val Cyan get() = palette.accent
    val Text get() = palette.text
    val Dim get() = palette.dim
    val Violet = Color(0xFF9A7BFF)
    val Magenta = Color(0xFFFF4FD2)
    val Green = Color(0xFF3DFFA8)
    val Amber = Color(0xFFFFC14D)
    val Red = Color(0xFFFF5C7A)

    val accents = linkedMapOf(
        "cyan" to Color(0xFF34E7FF),
        "magenta" to Color(0xFFFF4FD2),
        "violet" to Color(0xFF9A7BFF),
        "green" to Color(0xFF3DFFA8),
        "amber" to Color(0xFFFFC14D),
    )

    fun apply(textScale: Float, highContrast: Boolean, pureBlack: Boolean, accent: String) {
        palette = Palette(
            space = if (pureBlack) Color.Black else Color(0xFF070A14),
            panel = if (pureBlack) Color(0xFF07080C) else Color(0xFF0F1428),
            panel2 = if (pureBlack) Color(0xFF101218) else Color(0xFF161D38),
            line = if (highContrast) Color(0xFF5A6AAE) else Color(0xFF2A3566),
            accent = accents[accent] ?: Color(0xFF34E7FF),
            text = if (highContrast) Color.White else Color(0xFFE9ECFF),
            dim = if (highContrast) Color(0xFFD3D8F5) else Color(0xFFA3ACD6),
            textScale = textScale.coerceIn(0.8f, 1.6f),
            pureBlack = pureBlack,
        )
    }
}

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
    val p = Stellar.palette
    val scheme = darkColorScheme(
        primary = p.accent,
        onPrimary = Color(0xFF00151A),
        secondary = Stellar.Magenta,
        onSecondary = Color(0xFF2A0020),
        tertiary = Stellar.Violet,
        background = p.space,
        onBackground = p.text,
        surface = p.panel,
        onSurface = p.text,
        surfaceVariant = p.panel2,
        onSurfaceVariant = p.dim,
        surfaceContainer = p.panel,
        surfaceContainerHigh = p.panel2,
        surfaceContainerHighest = p.panel2,
        surfaceContainerLow = p.panel,
        surfaceContainerLowest = p.space,
        outline = p.line,
        outlineVariant = p.line,
        error = Stellar.Red,
    )
    val d = LocalDensity.current
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Default text color is light everywhere (transparent backgrounds used to fall back to black)
        CompositionLocalProvider(
            LocalContentColor provides p.text,
            LocalDensity provides Density(d.density, d.fontScale * p.textScale),
            content = content,
        )
    }
}
