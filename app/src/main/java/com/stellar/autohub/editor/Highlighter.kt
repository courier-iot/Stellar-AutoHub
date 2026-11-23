package com.stellar.autohub.editor

enum class Tok { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TYPE, TAG, ATTR, KEY, PUNCT }

class Span(val start: Int, val end: Int, val tok: Tok)

/** Small regex-based syntax highlighter. Fast enough to run on every keystroke for normal source files. */
object Highlighter {

    enum class Lang { KOTLIN, JAVA, XML, YAML, JSON, GRADLE_GROOVY, MARKDOWN, PROPERTIES, SHELL, PLAIN }

    fun langFor(path: String): Lang {
        val n = path.lowercase()
        return when {
            n.endsWith(".kt") || n.endsWith(".kts") -> Lang.KOTLIN
            n.endsWith(".java") -> Lang.JAVA
            n.endsWith(".xml") || n.endsWith(".html") || n.endsWith(".svg") -> Lang.XML
            n.endsWith(".yml") || n.endsWith(".yaml") -> Lang.YAML
            n.endsWith(".json") -> Lang.JSON
            n.endsWith(".gradle") -> Lang.GRADLE_GROOVY
            n.endsWith(".md") -> Lang.MARKDOWN
            n.endsWith(".properties") || n.endsWith(".pro") || n.endsWith(".cfg") || n.endsWith(".ini") || n.endsWith("gitignore") -> Lang.PROPERTIES
            n.endsWith(".sh") || n.endsWith("gradlew") -> Lang.SHELL
            else -> Lang.PLAIN
        }
    }

    private val kotlinKw = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for", "while", "do",
        "return", "break", "continue", "try", "catch", "finally", "throw", "in", "is", "as", "null", "true", "false", "this",
        "super", "private", "public", "internal", "protected", "override", "open", "abstract", "data", "sealed", "enum",
        "companion", "inline", "suspend", "lateinit", "const", "typealias", "by", "init", "get", "set", "operator", "infix",
        "vararg", "reified", "crossinline", "noinline", "annotation", "inner", "out", "where", "actual", "expect", "value",
    )
    private val javaKw = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static",
        "final", "abstract", "void", "int", "long", "boolean", "double", "float", "char", "byte", "short", "new", "return",
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally",
        "throw", "throws", "null", "true", "false", "this", "super", "synchronized", "volatile", "instanceof", "var", "record",
    )
    private val groovyKw = javaKw + setOf("def", "apply", "plugin", "plugins", "android", "dependencies", "implementation", "id")

    private val cStyle = Regex(
        "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$))" +
            "|(?<string>\"\"\"[\\s\\S]*?(?:\"\"\"|$)|\"(?:[^\"\\\\\\n]|\\\\.)*\"?|'(?:[^'\\\\\\n]|\\\\.)*'?)" +
            "|(?<ann>@[A-Za-z_][\\w.]*)" +
            "|(?<num>\\b(?:0x[0-9A-Fa-f_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFLuU]?)\\b)" +
            "|(?<word>\\b[A-Za-z_][\\w]*\\b)"
    )
    private val xml = Regex(
        "(?<comment><!--[\\s\\S]*?(?:-->|$))" +
            "|(?<tag></?[A-Za-z_][\\w:.\\-]*|/?>)" +
            "|(?<attr>\\b[A-Za-z_][\\w:.\\-]*(?=\\s*=))" +
            "|(?<string>\"[^\"]*\"?|'[^']*'?)"
    )
    private val yaml = Regex(
        "(?<comment>(?:^|(?<=\\s))#[^\\n]*)" +
            "|(?<key>^[ \\t]*-?[ \\t]*[\\w.\\-/\"' ]+?(?=:(?:\\s|$)))" +
            "|(?<string>\"(?:[^\"\\\\\\n]|\\\\.)*\"?|'[^'\\n]*'?)" +
            "|(?<ann>\\$\\{\\{[^}]*}}|\\$\\w+)" +
            "|(?<num>\\b\\d+(?:\\.\\d+)*\\b|\\b(?:true|false|null)\\b)",
        RegexOption.MULTILINE,
    )
    private val json = Regex(
        "(?<key>\"(?:[^\"\\\\\\n]|\\\\.)*\"(?=\\s*:))" +
            "|(?<string>\"(?:[^\"\\\\\\n]|\\\\.)*\"?)" +
            "|(?<num>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|\\b(?:true|false|null)\\b)"
    )
    private val props = Regex(
        "(?<comment>^[ \\t]*[#!][^\\n]*)|(?<key>^[ \\t]*[^=:#!\\s][^=:\\n]*(?=[=:]))",
        RegexOption.MULTILINE,
    )
    private val shell = Regex(
        "(?<comment>(?:^|(?<=\\s))#[^\\n]*)|(?<string>\"(?:[^\"\\\\]|\\\\.)*\"?|'[^']*'?)|(?<ann>\\$\\{?\\w+}?)",
        RegexOption.MULTILINE,
    )
    private val md = Regex(
        "(?<key>^#{1,6} [^\\n]*)|(?<string>`[^`\\n]+`|^```[^\\n]*)|(?<ann>\\*\\*[^*\\n]+\\*\\*|\\[[^]\\n]+]\\([^)\\n]+\\))",
        RegexOption.MULTILINE,
    )

    private fun groupTok(m: MatchResult, names: List<Pair<String, Tok>>): Tok? {
        for ((n, t) in names) if (m.groups[n] != null) return t
        return null
    }

    fun spans(text: String, lang: Lang): List<Span> {
        if (text.length > 400_000) return emptyList()
        val out = ArrayList<Span>()
        when (lang) {
            Lang.KOTLIN, Lang.JAVA, Lang.GRADLE_GROOVY -> {
                val kw = when (lang) { Lang.KOTLIN -> kotlinKw; Lang.JAVA -> javaKw; else -> groovyKw }
                for (m in cStyle.findAll(text)) {
                    val r = m.range
                    when {
                        m.groups["comment"] != null -> out += Span(r.first, r.last + 1, Tok.COMMENT)
                        m.groups["string"] != null -> out += Span(r.first, r.last + 1, Tok.STRING)
                        m.groups["ann"] != null -> out += Span(r.first, r.last + 1, Tok.ANNOTATION)
                        m.groups["num"] != null -> out += Span(r.first, r.last + 1, Tok.NUMBER)
                        m.groups["word"] != null -> {
                            val w = m.value
                            if (w in kw) out += Span(r.first, r.last + 1, Tok.KEYWORD)
                            else if (w[0].isUpperCase()) out += Span(r.first, r.last + 1, Tok.TYPE)
                        }
                    }
                }
            }
            Lang.XML -> add(out, text, xml, listOf("comment" to Tok.COMMENT, "tag" to Tok.TAG, "attr" to Tok.ATTR, "string" to Tok.STRING))
            Lang.YAML -> add(out, text, yaml, listOf("comment" to Tok.COMMENT, "key" to Tok.KEY, "string" to Tok.STRING, "ann" to Tok.ANNOTATION, "num" to Tok.NUMBER))
            Lang.JSON -> add(out, text, json, listOf("key" to Tok.KEY, "string" to Tok.STRING, "num" to Tok.NUMBER))
            Lang.PROPERTIES -> add(out, text, props, listOf("comment" to Tok.COMMENT, "key" to Tok.KEY))
            Lang.SHELL -> add(out, text, shell, listOf("comment" to Tok.COMMENT, "string" to Tok.STRING, "ann" to Tok.ANNOTATION))
            Lang.MARKDOWN -> add(out, text, md, listOf("key" to Tok.KEY, "string" to Tok.STRING, "ann" to Tok.ANNOTATION))
            Lang.PLAIN -> {}
        }
        return out
    }

    private fun add(out: MutableList<Span>, text: String, re: Regex, names: List<Pair<String, Tok>>) {
        for (m in re.findAll(text)) {
            val t = groupTok(m, names) ?: continue
            if (m.range.isEmpty()) continue
            out += Span(m.range.first, m.range.last + 1, t)
        }
    }
}
