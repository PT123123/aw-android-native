package net.activitywatch.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.ColorInt

/**
 * 界面渐变主题（笔记 / 任务模块共用）：「顶部 → 底部」的垂直线性渐变页面背景。
 *
 * 配色约定（新主题请照此扩展）：
 * - 一律低亮度暗色，顶部稍亮、底部更暗，哑光无高光、无纹理、无噪点，深色模式 UI 专用；
 * - 只做垂直渐变，不做对角 / 径向；
 * - 不要出现亮绿、亮蓝等跳出暗光氛围的高饱和色，整体偏冷、低对比度。
 *
 * @param top 渐变顶部（起始）色
 * @param bottom 渐变底部（结束）色
 */
data class GradientTheme(
    val id: String,
    val name: String,
    @ColorInt val top: Int,
    @ColorInt val bottom: Int,
)

object GradientThemes {

    /**
     * 翡翠绿：暗森林墨绿 #0F4938 向下平滑过渡到深海蓝青 #0A2442。
     * 绿色成分集中在上半部分，往下逐步降饱和并混入深蓝（绿 → 蓝绿 → 深蓝），没有生硬分界。
     */
    val JADE = GradientTheme("jade", "翡翠绿", 0xFF0F4938.toInt(), 0xFF0A2442.toInt())

    /** 全部可选主题；[DEFAULT] 为未设置时的取值 */
    val ALL: List<GradientTheme> = listOf(
        JADE,
        // 深空蓝：夜空深蓝 → 近黑蓝
        GradientTheme("deep_space", "深空蓝", 0xFF0E3560.toInt(), 0xFF071728.toInt()),
        // 暮光紫：暗紫罗兰 → 近黑紫
        GradientTheme("twilight", "暮光紫", 0xFF2C1A52.toInt(), 0xFF0F0720.toInt()),
        // 熔岩红：暗勃艮第酒红 → 焦黑
        GradientTheme("ember", "熔岩红", 0xFF4A1722.toInt(), 0xFF170609.toInt()),
        // 琥珀棕：暗焦糖棕 → 深咖
        GradientTheme("amber", "琥珀棕", 0xFF432B0F.toInt(), 0xFF150E05.toInt()),
        // 碧潭青：暗青绿 → 深潭
        GradientTheme("teal", "碧潭青", 0xFF0B3538.toInt(), 0xFF04161A.toInt()),
        // 樱夜粉：暗玫瑰紫 → 夜黑
        GradientTheme("rose", "樱夜粉", 0xFF3F1730.toInt(), 0xFF140617.toInt()),
        // 石墨灰：中性灰黑，最接近原本的纯深色页面
        GradientTheme("graphite", "石墨灰", 0xFF1E242B.toInt(), 0xFF090C10.toInt()),
    )

    val DEFAULT: GradientTheme = JADE

    fun byId(id: String?): GradientTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** 页面背景：铺满整页的无圆角垂直渐变 */
    fun pageDrawable(theme: GradientTheme): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.top, theme.bottom),
        )

    /**
     * 设置页色卡：带圆角的垂直渐变缩略图；选中时加一圈白描边（描边画在边界内，不改变色卡尺寸）。
     */
    fun swatchDrawable(
        theme: GradientTheme,
        selected: Boolean,
        cornerRadiusPx: Float,
        strokeWidthPx: Float,
    ): GradientDrawable = pageDrawable(theme).apply {
        cornerRadius = cornerRadiusPx
        if (selected) setStroke(strokeWidthPx.toInt().coerceAtLeast(1), 0xE6FFFFFF.toInt())
    }
}

/** 渐变主题偏好（本机保存，笔记 / 任务模块共用） */
object ThemePrefs {

    private const val PREFS = "ui_theme_prefs"
    private const val KEY_THEME_ID = "gradient_theme_id"

    fun themeId(context: Context): String =
        prefs(context).getString(KEY_THEME_ID, null) ?: GradientThemes.DEFAULT.id

    fun theme(context: Context): GradientTheme = GradientThemes.byId(themeId(context))

    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_THEME_ID, id).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object GradientBackground {

    /**
     * 给页面铺上当前主题的垂直渐变，并把 [chrome]（工具栏等）的背景让出来。
     *
     * 这些工具栏/根布局在 XML 里写着不透明的 `@color/inbox_bg` / `@color/aw_bg`，
     * 不置透明会把顶部渐变整条盖住，页面看起来就是「上面一块纯色、下面渐变」的割裂感。
     */
    fun applyPage(context: Context, root: View?, vararg chrome: View?) {
        root?.background = GradientThemes.pageDrawable(ThemePrefs.theme(context))
        chrome.forEach { it?.setBackgroundColor(Color.TRANSPARENT) }
    }
}
