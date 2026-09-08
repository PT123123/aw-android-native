package net.activitywatch.android.inbox

import android.content.Context
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.activitywatch.android.R

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

    /** 在光标处插入字面文本（有选区则替换），光标移到插入内容之后。井号/斜杠键用：插入 # / 本身 */
    fun insert(editor: EditText, text: String) {
        val editable = editor.text
        val selStart = editor.selectionStart.coerceAtLeast(0)
        val selEnd = editor.selectionEnd.coerceAtLeast(selStart)
        editable.replace(selStart, selEnd, text)
        editor.setSelection(selStart + text.length)
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

/**
 * 快速输入弹窗底部的 Markdown 工具栏（与 note_editor.xml 中的样式一致）。
 * 笔记页与任务页的快速输入共用；[dp] 由调用方按屏幕密度给出。
 */
fun buildMarkdownToolbar(
    ctx: Context,
    dp: (Int) -> Int,
    input: EditText,
): android.view.View {
    val subColor = ContextCompat.getColor(ctx, R.color.inbox_sub)
    fun item(text: String, desc: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            this.text = text
            contentDescription = desc
            gravity = android.view.Gravity.CENTER
            setTextColor(subColor)
            setOnClickListener { onClick() }
        }

    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    val items = listOf(
        // 井号/斜杠键插入字面字符（打 #标签 与层级 tag 的 a/b 分隔），不是 Markdown 语法
        item("#", "井号") { MarkdownTextActions.insert(input, "#") }.apply {
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        },
        item("B", "加粗") { MarkdownTextActions.toggleWrap(input, "**") }.apply {
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        },
        item("/", "斜杠") { MarkdownTextActions.insert(input, "/") }.apply { textSize = 18f },
        item("•", "无序列表") { MarkdownTextActions.toggleBullet(input) }.apply { textSize = 18f },
        item("1.", "有序列表") { MarkdownTextActions.toggleOrdered(input) }.apply { textSize = 15f },
    )
    // ?attr/selectableItemBackgroundBorderless 的水波纹背景
    val tv = android.util.TypedValue()
    ctx.theme.resolveAttribute(
        androidx.appcompat.R.attr.selectableItemBackgroundBorderless, tv, true
    )
    val ripple = ContextCompat.getDrawable(ctx, tv.resourceId)
    items.forEach { v ->
        v.background = ripple?.constantState?.newDrawable()?.mutate()
        val lp = LinearLayout.LayoutParams(dp(42), dp(38))
        lp.marginEnd = dp(4)
        row.addView(v, lp)
    }

    return HorizontalScrollView(ctx).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        isHorizontalScrollBarEnabled = false
        setPadding(dp(16), dp(2), dp(16), 0)
        addView(
            row,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}
