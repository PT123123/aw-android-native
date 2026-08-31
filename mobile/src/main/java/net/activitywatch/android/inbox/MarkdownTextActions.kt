package net.activitywatch.android.inbox

import android.widget.EditText

/**
 * 编辑器 markdown 工具栏的文本操作：
 * 行前缀类（标题循环、列表整体开关）作用于选区覆盖的所有行；
 * 包裹类（加粗/斜体）作用于选区或光标位置。
 */
object MarkdownTextActions {

    private val HEADING_RE = Regex("^(#{1,6})\\s+")
    private val BULLET_RE = Regex("^[-*+]\\s+")
    private val ORDERED_RE = Regex("^\\d+[.)]\\s+")
    private val ANY_LINE_PREFIX_RE = Regex("^(#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)")

    /** 标题：每行独立循环 无 → H1 → H2 → H3 → 无，同时替换掉已有的列表前缀 */
    fun cycleHeading(editor: EditText) = applyToLines(editor) { lines ->
        lines.map { line ->
            val level = HEADING_RE.find(line)?.groupValues?.get(1)?.length ?: 0
            val rest = ANY_LINE_PREFIX_RE.replaceFirst(line, "")
            when {
                level == 0 -> "# $rest"
                level >= 3 -> rest
                else -> "#".repeat(level + 1) + " $rest"
            }
        }
    }

    /** 无序列表：所有非空行都已有 "- " 时全部取消，否则给所有非空行加上 */
    fun toggleBullet(editor: EditText) = applyToLines(editor) { lines ->
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isNotEmpty() && nonBlank.all { BULLET_RE.containsMatchIn(it) }) {
            lines.map { BULLET_RE.replaceFirst(it, "") }
        } else {
            lines.map {
                if (it.isBlank()) it else "- " + ANY_LINE_PREFIX_RE.replaceFirst(it, "")
            }
        }
    }

    /** 有序列表：所有非空行都已有编号时全部取消，否则按顺序编号 */
    fun toggleOrdered(editor: EditText) = applyToLines(editor) { lines ->
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isNotEmpty() && nonBlank.all { ORDERED_RE.containsMatchIn(it) }) {
            lines.map { ORDERED_RE.replaceFirst(it, "") }
        } else {
            var n = 0
            lines.map {
                if (it.isBlank()) {
                    it
                } else {
                    n++
                    "$n. " + ANY_LINE_PREFIX_RE.replaceFirst(it, "")
                }
            }
        }
    }

    /** 加粗/斜体：有选区则包裹/解开，无选区则插入空标记并把光标放中间 */
    fun toggleWrap(editor: EditText, marker: String) {
        val editable = editor.text
        val text = editable.toString()
        val selStart = editor.selectionStart.coerceAtLeast(0)
        val selEnd = editor.selectionEnd.coerceAtLeast(selStart)

        if (selStart == selEnd) {
            editable.insert(selStart, marker + marker)
            editor.setSelection(selStart + marker.length)
            return
        }

        // 选区外侧已有成对标记 → 解开
        val before = text.substring(selStart.coerceAtLeast(marker.length) - marker.length, selStart)
        val after = text.substring(selEnd, (selEnd + marker.length).coerceAtMost(text.length))
        if (before == marker && after == marker) {
            editable.replace(
                selStart - marker.length,
                selEnd + marker.length,
                text.substring(selStart, selEnd),
            )
            editor.setSelection(selStart - marker.length, selEnd - marker.length)
            return
        }

        // 选区本身包含成对标记 → 解开
        val sel = text.substring(selStart, selEnd)
        if (sel.startsWith(marker) && sel.endsWith(marker) && sel.length >= marker.length * 2) {
            val inner = sel.substring(marker.length, sel.length - marker.length)
            editable.replace(selStart, selEnd, inner)
            editor.setSelection(selStart, selStart + inner.length)
            return
        }

        editable.replace(selStart, selEnd, marker + sel + marker)
        editor.setSelection(selStart + marker.length, selEnd + marker.length)
    }

    /** 对选区（或光标所在行）覆盖的所有行应用变换，一次性替换并保持选区合理 */
    private fun applyToLines(editor: EditText, transform: (List<String>) -> List<String>) {
        val text = editor.text.toString()
        val selStart = editor.selectionStart.coerceAtLeast(0)
        val selEnd = editor.selectionEnd.coerceAtLeast(selStart)
        val hadSelection = selStart != selEnd

        val start = text.lastIndexOf('\n', selStart - 1) + 1
        val end = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }
        val region = text.substring(start, end)
        val newText = transform(region.split('\n')).joinToString("\n")
        if (newText == region) return

        editor.text.replace(start, end, newText)
        if (hadSelection) {
            editor.setSelection(start, start + newText.length)
        } else {
            editor.setSelection(start + newText.length)
        }
    }
}
