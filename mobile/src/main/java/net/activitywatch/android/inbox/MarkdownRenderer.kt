package net.activitywatch.android.inbox

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import net.activitywatch.android.R

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

    fun render(context: Context, content: String): CharSequence {
        val ssb = SpannableStringBuilder(markwon(context).toMarkdown(content))
        val color = ContextCompat.getColor(context, R.color.inbox_accent)
        TAG_RE.findAll(ssb).forEach { match ->
            ssb.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return ssb
    }
}
