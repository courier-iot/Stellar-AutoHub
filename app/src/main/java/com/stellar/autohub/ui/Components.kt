package com.stellar.autohub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stellar.autohub.ui.theme.Mono
import com.stellar.autohub.ui.theme.Stellar
import kotlin.random.Random

@Composable
fun Starfield(modifier: Modifier = Modifier) {
    val stars = remember {
        val r = Random(7)
        List(90) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    Canvas(modifier.fillMaxSize()) {
        val pure = Stellar.palette.pureBlack
        drawRect(
            if (pure) Brush.verticalGradient(listOf(Color.Black, Color.Black))
            else Brush.verticalGradient(listOf(Color(0xFF0A0F24), Color(0xFF070A14), Color(0xFF0C0718)))
        )
        stars.forEach { (x, y, s) ->
            drawCircle(
                Color.White.copy(alpha = 0.15f + s * 0.55f),
                radius = 0.6f + s * 1.6f,
                center = Offset(x * size.width, y * size.height),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Starfield()
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Stellar.Text,
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Stellar.Text,
                        navigationIconContentColor = Stellar.Text,
                        actionIconContentColor = Stellar.Text,
                    ),
                )
            },
            floatingActionButton = floating,
            content = content,
        )
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color = Stellar.Line,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Stellar.Panel.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun Label(text: String, color: Color = Stellar.Cyan) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

fun statusColor(status: String?): Color = when (status) {
    "success" -> Stellar.Green
    "failure", "timed_out", "startup_failure" -> Stellar.Red
    "cancelled", "skipped", "none" -> Stellar.Dim
    "in_progress" -> Stellar.Cyan
    else -> Stellar.Amber
}

fun statusText(status: String?): String = when (status) {
    "success" -> "PASSED"
    "failure" -> "FAILED"
    "in_progress" -> "BUILDING"
    "queued", "waiting", "pending", "requested" -> "QUEUED"
    "cancelled" -> "CANCELLED"
    "none" -> "NO BUILD"
    null -> "…"
    else -> status.uppercase()
}

@Composable
fun ErrorBox(text: String) {
    Panel(accent = Stellar.Red) {
        Text(text, color = Stellar.Red)
    }
}

@Composable
fun InfoBox(text: String, color: Color = Stellar.Amber) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MonoText(text: String, color: Color = Stellar.Text, modifier: Modifier = Modifier) {
    Text(text, fontFamily = Mono, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

@Composable
fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Stellar.Dim)
    }
}

fun Context.copy(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

fun ago(time: Long): String {
    val s = (System.currentTimeMillis() - time) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
