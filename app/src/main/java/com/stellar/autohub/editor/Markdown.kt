package com.stellar.autohub.editor

sealed class MdBlock {
    class Heading(val level: Int, val text: String) : MdBlock()
    class Para(val text: String) : MdBlock()
    class Bullet(val text: String, val indent: Int, val marker: String) : MdBlock()
    class Code(val text: String, val lang: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Table(val rows: List<List<String>>) : MdBlock()
    object Rule : MdBlock()
}

/** Inline pieces of a markdown line. */
sealed class MdInline {
    class Plain(val text: String) : MdInline()
    class Bold(val text: String) : MdInline()
    class Italic(val text: String) : MdInline()
    class Code(val text: String) : MdInline()
    class Link(val text: String, val url: String) : MdInline()
}

/** Just enough Markdown for READMEs: headings, lists, code blocks, quotes, tables, links, bold/italic/code. */
object Markdown {
    private val bullet = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)[^)]*\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")

    fun blocks(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r", "").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.clear() }
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.trimStart().startsWith("```") -> {
                    flush()
                    val lang = l.trimStart().removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out += MdBlock.Code(code.toString().trimEnd('\n'), lang)
                }
                heading.matches(l) -> { flush(); val m = heading.find(l)!!; out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]) }
                rule.matches(l) -> { flush(); out += MdBlock.Rule }
                bullet.matches(l) -> {
                    flush(); val m = bullet.find(l)!!
                    out += MdBlock.Bullet(m.groupValues[3], m.groupValues[1].length / 2, if (m.groupValues[2][0].isDigit()) m.groupValues[2] else "•")
                }
                l.trimStart().startsWith(">") -> { flush(); out += MdBlock.Quote(l.trimStart().removePrefix(">").trim()) }
                l.trim().startsWith("|") && l.trim().endsWith("|") -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    out += MdBlock.Table(rows)
                    continue
                }
                l.isBlank() -> flush()
                else -> para.append(l.trim()).append(' ')
            }
            i++
        }
        flush()
        return out
    }

    fun inlines(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        var last = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > last) out += MdInline.Plain(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> MdInline.Bold(g[1])
                g[2].isNotEmpty() -> MdInline.Bold(g[2])
                g[3].isNotEmpty() -> MdInline.Code(g[3])
                g[4].isNotEmpty() -> MdInline.Link(g[4], g[5])
                g[6].isNotEmpty() -> MdInline.Italic(g[6])
                else -> MdInline.Italic(g[7])
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdInline.Plain(text.substring(last))
        return out
    }
}
