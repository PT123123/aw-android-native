package net.activitywatch.android.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.activitywatch.android.R
import kotlin.math.ceil
import kotlin.math.min

/**
 * 「碎片」热力图：单日的二维时间块。
 * 横轴 = 一天的 24 小时（24 列），纵轴 = 每小时内的 5 分钟槽（12 行，**自下而上 :00→:55**，
 * 即最底行 :00、最顶行 :55），每个圆点代表当天一个 5 分钟的使用强度，颜色越深越密集（GitHub 热力图风格）。
 * 左侧标分钟刻度，底部标小时刻度与「少→多」图例。
 */
class UsageHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLS = 24
        private const val ROWS = 12
    }

    /** 288 个 5 分钟槽的使用秒数，下标 = row*24+col（row = 槽，col = 小时） */
    private var slots: List<Double> = emptyList()
    private var maxSec = 0.0

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.aw_text_secondary)
        textSize = 10f * density
    }

    /** 下标 0..4：空 / 由浅到深四档 */
    private val levelColors = IntArray(5)

    private val density get() = resources.displayMetrics.density
    private val rowH = 15f * density
    private val axisH = 18f * density
    private val gutterW = 26f * density
    private val dotGap = 3f * density
    private val padTop = 4f * density
    private val padSide = 4f * density

    init {
        val base = ContextCompat.getColor(context, R.color.aw_accent)
        levelColors[0] = ContextCompat.getColor(context, R.color.aw_bar_track)
        levelColors[1] = ColorUtils.setAlphaComponent(base, 0x40)
        levelColors[2] = ColorUtils.setAlphaComponent(base, 0x66)
        levelColors[3] = ColorUtils.setAlphaComponent(base, 0x99)
        levelColors[4] = base
    }

    fun submit(slots: List<Double>) {
        this.slots = slots
        maxSec = slots.maxOrNull() ?: 0.0
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (padTop + ROWS * rowH + axisH).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f) return
        val gridLeft = gutterW
        val gridW = w - gridLeft - padSide
        if (gridW <= 0f) return
        val colW = gridW / COLS
        val radius = (min(colW, rowH) - dotGap) / 2f

        // 圆点阵：行 = 5 分钟槽（**自下而上 :00→:55**，最底行 :00），列 = 小时
        for (disp in 0 until ROWS) {
            val r = ROWS - 1 - disp // 显示行（0 = 最顶）→ 数据行（0 = :00）
            val cy = padTop + disp * rowH + rowH / 2f
            for (c in 0 until COLS) {
                val sec = slots.getOrElse(r * COLS + c) { 0.0 }
                val level = when {
                    sec <= 0.0 || maxSec <= 0.0 -> 0
                    else -> ceil(sec / maxSec * 4).toInt().coerceIn(1, 4)
                }
                dotPaint.color = levelColors[level]
                canvas.drawCircle(gridLeft + c * colW + colW / 2f, cy, radius, dotPaint)
            }
        }

        // 左侧分钟刻度：每 15 分钟一行（同样自下而上，:00 在最底、:45 靠近顶部）
        val rowLabels = mapOf(0 to ":00", 3 to ":15", 6 to ":30", 9 to ":45")
        for ((slot, label) in rowLabels) {
            val baseline = padTop + (ROWS - 1 - slot) * rowH + rowH / 2f + 3f * density
            canvas.drawText(label, 0f, baseline, textPaint)
        }

        // 底部小时刻度：0 / 6 / 12 / 18
        val axisY = padTop + ROWS * rowH + axisH * 0.7f
        for (h in intArrayOf(0, 6, 12, 18)) {
            canvas.drawText(h.toString(), gridLeft + h * colW, axisY, textPaint)
        }

        // 图例：少 ● ● ● ● 多（右下角）
        val legendR = 3.5f * density
        var rx = w - padSide
        canvas.drawText("多", rx - textPaint.measureText("多"), axisY, textPaint)
        rx -= textPaint.measureText("多") + 3f * density
        for (lv in 4 downTo 1) {
            dotPaint.color = levelColors[lv]
            canvas.drawCircle(rx - legendR, axisY - 3.5f * density, legendR, dotPaint)
            rx -= legendR * 2 + 3f * density
        }
        canvas.drawText("少", rx - textPaint.measureText("少"), axisY, textPaint)
    }
}
