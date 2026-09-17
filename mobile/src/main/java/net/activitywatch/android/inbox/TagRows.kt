package net.activitywatch.android.inbox

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.activitywatch.android.R

/**
 * 标签展示（列表卡片标签行 / 查看笔记页顶部标签行）。
 *
 * 只渲染「笔记真正的标签」（服务端 tags 字段），不扫描正文：
 * 正文里的 `#xxx` 属于纯文本，标签由创建时解析或「扫描标签」手动登记后写入 tags。
 * 层级 tag（`项目/工作`）整段着色、每段独立可点，点击回调「到该段为止的路径」。
 */

/** 一行 `#标签` spannable：标签着色 + 每段可点（列表卡片用，配合 Adapter 的命中测试） */
fun buildTagRowSpannable(
    context: Context,
    tags: List<String>,
    onTagClick: (String) -> Unit,
): CharSequence {
    val color = ContextCompat.getColor(context, R.color.inbox_accent)
    val ssb = SpannableStringBuilder()
    tags.forEachIndexed { index, tag ->
        if (index > 0) ssb.append("   ")
        val segs = tagSegments(tag)
        if (segs.size <= 1) {
            val start = ssb.length
            ssb.append("#$tag")
            ssb.setSpan(TagSpan(tag, color, onTagClick), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            var acc = ""
            segs.forEachIndexed { k, seg ->
                if (k > 0) ssb.append("/")
                val start = ssb.length
                ssb.append(seg)
                acc = if (k == 0) seg else "$acc/$seg"
                ssb.setSpan(TagSpan(acc, color, onTagClick), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    return ssb
}

/**
 * 把 tags 渲染成一排 chip 到 [container]（查看笔记页顶部）。
 * 单段 tag 显示 `#tag`；层级 tag 按段拼 `项目 / 工作`，整块点击 = 按完整路径筛选。
 */
fun buildTagChips(container: LinearLayout, tags: List<String>, onTagClick: (String) -> Unit) {
    val ctx = container.context
    val density = ctx.resources.displayMetrics.density
    val dp = { v: Int -> (v * density).toInt() }
    container.removeAllViews()
    tags.forEach { tag ->
        val chip = TextView(ctx).apply {
            text = if (tagSegments(tag).size <= 1) "#$tag" else "#${formatTagBreadcrumb(tag)}"
            setTextColor(ContextCompat.getColor(ctx, R.color.inbox_accent))
            textSize = 13f
            background = ContextCompat.getDrawable(ctx, R.drawable.inbox_tag_chip_bg)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onTagClick(tag) }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.marginEnd = dp(6)
        lp.bottomMargin = dp(4)
        container.addView(chip, lp)
    }
}

/** 标签行的通用着色（弹窗里展示扫描到的 tag 用） */
fun tagTextColor(context: Context, registered: Boolean): Int =
    if (registered) ContextCompat.getColor(context, R.color.inbox_accent)
    else ContextCompat.getColor(context, R.color.inbox_sub)
