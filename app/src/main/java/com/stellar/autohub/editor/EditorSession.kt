package com.stellar.autohub.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Open editor tabs (kept while the app runs; edits to repo files are also saved as drafts). */
object EditorSession {
    enum class Source { REPO, UPLOAD }

    class Doc(
        val repoId: String,
        val branch: String,
        val path: String,
        val source: Source,
        val baseSha: String?,
        /** Content on GitHub (or in the upload) when the tab was opened. */
        val original: String,
        initial: String,
        /** A file that doesn't exist on GitHub yet: always counts as a change. */
        val isNew: Boolean = false,
    ) {
        val key get() = "$source|$repoId|$branch|$path"
        var value by mutableStateOf(TextFieldValue(initial))
        var jumpLine by mutableStateOf<Int?>(null)
        private val undo = ArrayDeque<String>()
        private val redo = ArrayDeque<String>()
        private var lastPush = 0L
        var canUndo by mutableStateOf(false)
        var canRedo by mutableStateOf(false)

        val text get() = value.text
        val changed get() = isNew || value.text != original

        /** Call with the new value from the text field; groups fast typing into one undo step. */
        fun edit(v: TextFieldValue) {
            if (v.text != value.text) {
                val now = System.currentTimeMillis()
                if (undo.isEmpty() || now - lastPush > 700) {
                    undo.addLast(value.text); if (undo.size > 300) undo.removeFirst()
                }
                lastPush = now
                redo.clear()
            }
            value = v
            sync()
        }

        fun replaceAll(newText: String, cursor: Int = newText.length) {
            undo.addLast(value.text); redo.clear(); lastPush = 0
            value = TextFieldValue(newText, TextRange(cursor.coerceIn(0, newText.length)))
            sync()
        }

        fun undo() {
            val prev = undo.removeLastOrNull() ?: return
            redo.addLast(value.text)
            value = TextFieldValue(prev, TextRange(prev.length.coerceAtMost(value.selection.start)))
            lastPush = 0; sync()
        }

        fun redo() {
            val next = redo.removeLastOrNull() ?: return
            undo.addLast(value.text)
            value = TextFieldValue(next, TextRange(next.length.coerceAtMost(value.selection.start)))
            lastPush = 0; sync()
        }

        private fun sync() { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }
    }

    val docs = mutableStateListOf<Doc>()
    var currentKey by mutableStateOf<String?>(null)

    fun find(source: Source, repoId: String, branch: String, path: String) =
        docs.find { it.source == source && it.repoId == repoId && it.branch == branch && it.path == path }

    fun open(doc: Doc): Doc {
        val existing = docs.find { it.key == doc.key }
        val d = existing ?: doc.also { docs += it }
        currentKey = d.key
        return d
    }

    fun close(doc: Doc) {
        val i = docs.indexOf(doc)
        docs.remove(doc)
        if (currentKey == doc.key) currentKey = docs.getOrNull((i - 1).coerceAtLeast(0))?.key
    }

    fun current(): Doc? = docs.find { it.key == currentKey }

    /** Line number (1-based) -> character offset. */
    fun offsetOfLine(text: String, line: Int): Int {
        var l = 1
        var i = 0
        while (l < line && i < text.length) { if (text[i] == '\n') l++; i++ }
        return i
    }

    fun lineOfOffset(text: String, offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1
}
