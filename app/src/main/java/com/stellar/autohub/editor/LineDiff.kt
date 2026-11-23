package com.stellar.autohub.editor

enum class DiffKind { SAME, ADD, DEL, GAP }

class DiffLine(val kind: DiffKind, val oldNo: Int, val newNo: Int, val text: String)

/** Line diff (Myers' algorithm), plus a compact view with only a few lines of context around changes. */
object LineDiff {

    fun diff(oldText: String, newText: String): List<DiffLine> {
        val a = if (oldText.isEmpty()) emptyList() else oldText.split('\n')
        val b = if (newText.isEmpty()) emptyList() else newText.split('\n')
        // Trim common prefix/suffix first: keeps Myers fast for typical small edits
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)

        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until pre) out += DiffLine(DiffKind.SAME, i + 1, i + 1, a[i])
        out += middle(am, bm, pre)
        for (k in 0 until suf) {
            val ai = a.size - suf + k
            val bi = b.size - suf + k
            out += DiffLine(DiffKind.SAME, ai + 1, bi + 1, a[ai])
        }
        return out
    }

    private fun middle(a: List<String>, b: List<String>, offset: Int): List<DiffLine> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.mapIndexed { j, s -> DiffLine(DiffKind.ADD, 0, offset + j + 1, s) }
        if (m == 0) return a.mapIndexed { i, s -> DiffLine(DiffKind.DEL, offset + i + 1, 0, s) }
        val max = n + m
        if (max > 40_000) {
            // Too big to diff smartly on a phone: show as replaced
            return a.mapIndexed { i, s -> DiffLine(DiffKind.DEL, offset + i + 1, 0, s) } +
                b.mapIndexed { j, s -> DiffLine(DiffKind.ADD, 0, offset + j + 1, s) }
        }
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        loop@ for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) v[max + k + 1] else v[max + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[max + k] = x
                if (x >= n && y >= m) { found = true; break@loop }
                k += 2
            }
            if (trace.size > 3000) break
        }
        if (!found) {
            return a.mapIndexed { i, s -> DiffLine(DiffKind.DEL, offset + i + 1, 0, s) } +
                b.mapIndexed { j, s -> DiffLine(DiffKind.ADD, 0, offset + j + 1, s) }
        }
        // Backtrack
        val rev = ArrayList<DiffLine>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[max + k - 1] < vd[max + k + 1])) k + 1 else k - 1
            val prevX = vd[max + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                rev += DiffLine(DiffKind.SAME, offset + x, offset + y, a[x - 1]); x--; y--
            }
            if (d > 0) {
                if (x == prevX) { rev += DiffLine(DiffKind.ADD, 0, offset + y, b[y - 1]); y-- }
                else { rev += DiffLine(DiffKind.DEL, offset + x, 0, a[x - 1]); x-- }
            }
        }
        return rev.asReversed()
    }

    /** Only changed lines plus [context] lines around them; long unchanged runs become GAP lines. */
    fun compact(lines: List<DiffLine>, context: Int = 3): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (l.kind != DiffKind.SAME) for (j in (i - context)..(i + context)) if (j in lines.indices) keep[j] = true
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { i, l ->
            if (keep[i]) {
                if (skipped > 0) { out += DiffLine(DiffKind.GAP, 0, 0, "⋯ $skipped unchanged line(s)"); skipped = 0 }
                out += l
            } else skipped++
        }
        if (skipped > 0 && out.isNotEmpty()) out += DiffLine(DiffKind.GAP, 0, 0, "⋯ $skipped unchanged line(s)")
        return out
    }

    fun stats(lines: List<DiffLine>) = lines.count { it.kind == DiffKind.ADD } to lines.count { it.kind == DiffKind.DEL }

    /** Turns a GitHub unified "patch" string into diff lines. */
    fun fromPatch(patch: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var o = 0
        var n = 0
        val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)""")
        for (line in patch.lines()) {
            val h = hunk.find(line)
            when {
                h != null -> {
                    o = h.groupValues[1].toInt(); n = h.groupValues[2].toInt()
                    out += DiffLine(DiffKind.GAP, 0, 0, "@@ line $n${h.groupValues[3]}")
                }
                line.startsWith("+") -> { out += DiffLine(DiffKind.ADD, 0, n, line.drop(1)); n++ }
                line.startsWith("-") -> { out += DiffLine(DiffKind.DEL, o, 0, line.drop(1)); o++ }
                line.startsWith("\\") -> {}
                else -> { out += DiffLine(DiffKind.SAME, o, n, line.drop(1)); o++; n++ }
            }
        }
        return out
    }
}
