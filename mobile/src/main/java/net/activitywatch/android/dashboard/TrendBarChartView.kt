package net.activitywatch.android.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import net.activitywatch.android.R
import kotlin.math.ceil

/**
 * 趋势柱状图：一天一根柱，柱高=当天总时长，柱内按当天 Top3 应用堆叠着色。
 *
 * 不引第三方图表库——项目里没有，也不值得为一个页面拉依赖。
 */
class TrendBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var days: List<TrendDay> = emptyList()
    private var maxSec: Double = 0.0

    private val density get() = resources.displayMetrics.density

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.aw_bar_track)
        gridPaint.color = ContextCompat.getColor(context, R.color.aw_border)
        textPaint.color = ContextCompat.getColor(context, R.color.aw_text_secondary)
        textPaint.textSize = 10f * density
    }

    fun submit(days: List<TrendDay>) {
        this.days = days
        maxSec = days.maxOfOrNull { it.totalSec } ?: 0.0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || days.isEmpty() || maxSec <= 0.0) return

        val labelH = 16f * density
        val chartH = (h - labelH).coerceAtLeast(10f)
        val n = days.size
        val gap = 2f * density
        // 只有一两天数据时，均分出来的柱子会宽得离谱，封顶后整体居中
        val barW = ((w - gap * (n - 1)) / n).coerceAtLeast(2f).coerceAtMost(MAX_BAR_W * density)
        val startX = ((w - (n * barW + (n - 1) * gap)) / 2f).coerceAtLeast(0f)

        // 参考线：50% / 100% 两档
        for (f in listOf(0.5f, 1.0f)) {
            val y = chartH - chartH * f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        for (i in days.indices) {
            val d = days[i]
            val left = startX + i * (barW + gap)
            val barH = ((d.totalSec / maxSec) * chartH).toFloat().coerceAtLeast(2f)

            // 先铺整根柱（track 色），再自底向上覆盖 Top3 的色段，
            // 没被覆盖的部分自然表示「其他应用」
            canvas.drawRect(left, chartH - barH, left + barW, chartH, trackPaint)

            var bottom = chartH
            for (item in d.items) {
                val segH = ((item.durationSec / maxSec) * chartH).toFloat()
                if (segH <= 0f) continue
                barPaint.color = ActivityPalette.color(context, item.colorIndex)
                canvas.drawRect(left, bottom - segH, left + barW, bottom, barPaint)
                bottom -= segH
            }
        }

        // 日期标签按抽样间隔绘制，避免天数多时糊成一片
        val step = ceil(n / 5.0).toInt().coerceAtLeast(1)
        for (i in days.indices step step) {
            val left = startX + i * (barW + gap)
            val x = (left + barW / 2f).coerceIn(barW / 2f, w - barW / 2f)
            canvas.drawText(formatDayLabel(days[i].dayStartMs), x, h - 4f * density, textPaint)
        }
    }

    companion object {
        /** 单根柱的最大宽度（dp）：天数很少时不让柱子撑满整屏。 */
        private const val MAX_BAR_W = 44f
    }
}
