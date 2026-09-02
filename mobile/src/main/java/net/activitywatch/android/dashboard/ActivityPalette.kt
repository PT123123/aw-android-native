package net.activitywatch.android.dashboard

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import net.activitywatch.android.R

/**
 * 分类色板。
 *
 * 把应用/网站标签按「总时长排名」映射到 aw_cat_1..8，未进前 8 的一律用灰色 (OTHER)。
 * 同一个标签在 Timeline 色带、按小时列表、Trends 堆叠柱里拿到的是同一个颜色，
 * 这样跨视图对照时颜色语义是一致的。
 */
object ActivityPalette {
    /** 未进入前 N 名的标签使用的色索引。 */
    const val OTHER = -1

    /** 进入排名、会单独分配颜色的标签数量上限。 */
    const val RANKED = 8

    private val CAT_RES = intArrayOf(
        R.color.aw_cat_1,
        R.color.aw_cat_2,
        R.color.aw_cat_3,
        R.color.aw_cat_4,
        R.color.aw_cat_5,
        R.color.aw_cat_6,
        R.color.aw_cat_7,
        R.color.aw_cat_8,
    )

    @ColorInt
    fun color(context: Context, colorIndex: Int): Int {
        val res = if (colorIndex < 0) R.color.aw_text_disabled else CAT_RES[colorIndex % CAT_RES.size]
        return ContextCompat.getColor(context, res)
    }
}
