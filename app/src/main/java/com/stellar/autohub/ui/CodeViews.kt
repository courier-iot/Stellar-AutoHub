package com.stellar.autohub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.stellar.autohub.editor.*
import com.stellar.autohub.ui.theme.Mono
import com.stellar.autohub.ui.theme.Stellar

object CodeColors {
    fun of(t: Tok): SpanStyle = when (t) {
        Tok.KEYWORD -> SpanStyle(color = Color(0xFFFF79C6), fontWeight = FontWeight.SemiBold)
        Tok.STRING -> SpanStyle(color = Color(0xFFA5E887))
        Tok.COMMENT -> SpanStyle(color = Color(0xFF7884B0), fontStyle = FontStyle.Italic)
        Tok.NUMBER -> SpanStyle(color = Color(0xFFFFC14D))
        Tok.ANNOTATION -> SpanStyle(color = Color(0xFFB79CFF))
        Tok.TYPE -> SpanStyle(color = Color(0xFF7FDBFF))
        Tok.TAG -> SpanStyle(color = Color(0xFFFF79C6))
        Tok.ATTR -> SpanStyle(color = Color(0xFFFFC14D))
        Tok.KEY -> SpanStyle(color = Stellar.Cyan)
        Tok.PUNCT -> SpanStyle(color = Stellar.Dim)
    }
}

fun highlighted(text: String, lang: Highlighter.Lang, extra: List<TextRange> = emptyList(), current: Int = -1): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (s in Highlighter.spans(text, lang)) if (s.end <= text.length) addStyle(CodeColors.of(s.tok), s.start, s.end)
        extra.forEachIndexed { i, r ->
            if (r.end <= text.length) addStyle(
                SpanStyle(background = if (i == current) Color(0xAAFFC14D) else Color(0x55FFC14D)), r.start, r.end,
            )
        }
    }

/** Syntax colors (and search hits) drawn over the text field without changing the text. */
class HighlightTransformation(
    private val lang: Highlighter.Lang,
    private val hits: List<TextRange>,
    private val current: Int,
) : VisualTransformation {
    private var lastText: String? = null
    private var last: AnnotatedString? = null
    override fun filter(text: AnnotatedString): TransformedText {
        val cached = last
        val out = if (cached != null && lastText == text.text) cached
        else highlighted(text.text, lang, hits, current).also { lastText = text.text; last = it }
        return TransformedText(out, OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is HighlightTransformation && other.lang == lang && other.hits == hits && other.current == current
    override fun hashCode() = lang.hashCode() * 31 + hits.hashCode() + current
}

val codeStyle: TextStyle @Composable get() = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Stellar.Text)

/**
 * Editable code with line numbers. Line numbers follow wrapped lines too, and only the visible
 * ones are drawn so big files stay smooth.
 */
@Composable
fun CodeArea(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    lang: Highlighter.Lang,
    wrap: Boolean,
    vScroll: ScrollState,
    onLayout: (TextLayoutResult) -> Unit,
    hits: List<TextRange> = emptyList(),
    currentHit: Int = -1,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val style = codeStyle
    val transformation = remember(lang, hits, currentHit) { HighlightTransformation(lang, hits, currentHit) }
    var viewport by remember { mutableIntStateOf(2000) }

    Row(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.height }
            .verticalScroll(vScroll)
    ) {
        // Line numbers
        val l = layout
        val gutter = with(density) { (value.text.count { it == '\n' } + 1).toString().length.coerceAtLeast(2).times(9).sp.toDp() + 12.dp }
        Box(Modifier.width(gutter).height(with(density) { (l?.size?.height ?: 0).toDp() }.coerceAtLeast(40.dp)).background(Stellar.Panel)) {
            if (l != null) {
                val t = value.text
                val starts = remember(t) {
                    val list = ArrayList<Int>(); list += 0
                    var k = t.indexOf('\n')
                    while (k >= 0) { list += k + 1; k = t.indexOf('\n', k + 1) }
                    list
                }
                fun topOf(i: Int) = l.getLineTop(l.getLineForOffset(starts[i].coerceAtMost(l.layoutInput.text.length))).toInt()
                val top = vScroll.value - 400
                val bottom = vScroll.value + viewport + 400
                // binary search the first line in view
                var lo = 0
                var hi = starts.size - 1
                while (lo < hi) { val mid = (lo + hi) / 2; if (topOf(mid) < top) lo = mid + 1 else hi = mid }
                var i = lo
                while (i < starts.size) {
                    val y = topOf(i)
                    if (y > bottom) break
                    Text(
                        "${i + 1}", style = style.copy(color = Stellar.Dim.copy(alpha = 0.7f), fontSize = 11.sp),
                        modifier = Modifier.offset { IntOffset(0, y) }.padding(start = 4.dp),
                    )
                    i++
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                .padding(start = 8.dp, end = 8.dp, bottom = 120.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = readOnly,
                textStyle = style,
                cursorBrush = SolidColor(Stellar.Cyan),
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                onTextLayout = { layout = it; onLayout(it) },
                modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 600.dp),
            )
        }
    }
}

/** Read-only diff rendering. */
@Composable
fun DiffLines(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val style = codeStyle
    val h = rememberScrollState()
    Column(modifier.horizontalScroll(h)) {
        lines.forEach { l -> DiffRow(l, style) }
    }
}

@Composable
fun DiffRow(l: DiffLine, style: TextStyle = codeStyle) {
    val (bg, fg, mark) = when (l.kind) {
        DiffKind.ADD -> Triple(Color(0x2A3DFFA8), Stellar.Green, "+")
        DiffKind.DEL -> Triple(Color(0x2AFF5C7A), Stellar.Red, "−")
        DiffKind.GAP -> Triple(Stellar.Panel2, Stellar.Dim, " ")
        DiffKind.SAME -> Triple(Color.Transparent, Stellar.Text.copy(alpha = 0.8f), " ")
    }
    Row(Modifier.background(bg).padding(horizontal = 4.dp)) {
        Text(if (l.oldNo > 0) "${l.oldNo}" else "", style = style.copy(color = Stellar.Dim, fontSize = 11.sp), modifier = Modifier.width(38.dp))
        Text(if (l.newNo > 0) "${l.newNo}" else "", style = style.copy(color = Stellar.Dim, fontSize = 11.sp), modifier = Modifier.width(38.dp))
        Text("$mark ${l.text}", style = style.copy(color = fg), softWrap = false)
    }
}

/** Renders a README-style Markdown document. */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val blocks = remember(text) { Markdown.blocks(text) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    inline(b.text), fontWeight = FontWeight.Bold, color = Stellar.Text,
                    fontSize = when (b.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp },
                    modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
                )
                is MdBlock.Para -> LinkText(inline(b.text)) { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                is MdBlock.Bullet -> Row(Modifier.padding(start = (12 * b.indent).dp)) {
                    Text("${b.marker} ", color = Stellar.Cyan)
                    LinkText(inline(b.text)) { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().background(Stellar.Panel2, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(10.dp)
                ) {
                    val lang = Highlighter.langFor("x." + when (b.lang) { "kotlin" -> "kt"; "yaml" -> "yml"; "" -> "txt"; else -> b.lang })
                    Text(highlighted(b.text, lang), style = codeStyle, softWrap = false)
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(Stellar.Violet))
                    Spacer(Modifier.width(8.dp))
                    Text(inline(b.text), color = Stellar.Dim, fontStyle = FontStyle.Italic)
                }
                is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    b.rows.forEachIndexed { i, row ->
                        Row {
                            row.forEach { cell ->
                                Text(inline(cell), fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(140.dp).padding(4.dp))
                            }
                        }
                        if (i == 0) HorizontalDivider(color = Stellar.Line)
                    }
                }
                MdBlock.Rule -> HorizontalDivider(color = Stellar.Line)
            }
        }
    }
}

@Composable
private fun inline(text: String): AnnotatedString {
    val accent = Stellar.Cyan
    val panel = Stellar.Panel2
    return remember(text, accent) {
        buildAnnotatedString {
            for (p in Markdown.inlines(text)) when (p) {
                is MdInline.Plain -> append(p.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.text) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(p.text) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = Mono, background = panel, color = Color(0xFFA5E887))) { append(p.text) }
                is MdInline.Link -> {
                    pushStringAnnotation("url", p.url)
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(p.text) }
                    pop()
                }
            }
        }
    }
}

@Composable
private fun LinkText(text: AnnotatedString, onLink: (String) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, color = Stellar.Text, style = MaterialTheme.typography.bodyMedium, onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout ?: return@detectTapGestures
                val off = l.getOffsetForPosition(pos)
                text.getStringAnnotations("url", off, off).firstOrNull()?.let { onLink(it.item) }
            }
        },
    )
}
