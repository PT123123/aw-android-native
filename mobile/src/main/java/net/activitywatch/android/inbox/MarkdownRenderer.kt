package net.activitywatch.android.inbox

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.core.content.ContextCompat
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import net.activitywatch.android.R

/**
 * 正文里的 #标签：着色 + 可点击。
 *
 * 卡片根节点自己吃掉了全部触摸事件（手势检测），LinkMovementMethod 拿不到事件，
 * 所以这里只用 [TagSpan] 标记范围，由 [InboxAdapter] 手动命中测试后回调 [TagSpan.onClick]。
 */
class TagSpan(
    val tag: String,
    private val color: Int,
    private val onTagClick: (String) -> Unit,
) : ClickableSpan() {
    override fun onClick(widget: View) = onTagClick(tag)

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = false
    }
}

/** 展示侧 markdown 渲染：Markwon 输出后叠加原有 #标签 着色 */
object MarkdownRenderer {

    // 与 InboxAdapter 原有高亮规则保持一致
    private val TAG_RE = Regex("#[^\\s#,，。.！!？?；;：:+]+\\.?")

    @Volatile
    private var instance: Markwon? = null

    private fun markwon(context: Context): Markwon {
        return instance ?: synchronized(this) {
            instance ?: Markwon.builder(context.applicationContext)
                // 普通换行保持换行显示，而不是被 commonmark 合并成空格
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .build()
                .also { instance = it }
        }
    }

    /** @param onTagClick 传入时 #标签 可点击（回调标签名，不含 #）；不传则只着色 */
    fun render(context: Context, content: String, onTagClick: ((String) -> Unit)? = null): CharSequence {
        val ssb = SpannableStringBuilder(markwon(context).toMarkdown(content))
        val color = ContextCompat.getColor(context, R.color.inbox_accent)
        TAG_RE.findAll(ssb).forEach { match ->
            val tag = match.value.removePrefix("#")
            if (onTagClick == null) {
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else if (!tag.contains('/')) {
                ssb.setSpan(
                    TagSpan(tag, color, onTagClick),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                // 层级 tag（#项目/工作/xx）：整段保持高亮色，同时每个段独立可点，
                // 点击回调「到该段为止的路径」（如点 工作 → 项目/工作），配合服务端段边界前缀匹配筛选。
                // 标签字符集不含空白，段与原文位置一一对应；分隔符 # / 不在 span 内，点上去走普通单击。
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                var off = 0
                var acc = ""
                for (seg in tag.split('/')) {
                    val start = match.range.first + 1 + off
                    acc = if (acc.isEmpty()) seg else "$acc/$seg"
                    if (seg.isNotEmpty()) {
                        ssb.setSpan(
                            TagSpan(acc, color, onTagClick),
                            start,
                            start + seg.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    off += seg.length + 1
                }
            }
        }
        return ssb
    }
}
