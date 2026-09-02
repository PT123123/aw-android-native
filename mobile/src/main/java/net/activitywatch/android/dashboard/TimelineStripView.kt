package net.activitywatch.android.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import net.activitywatch.android.R
import kotlin.math.max
import kotlin.math.min

/**
 * 时间线色带：把一段时间窗内的活动段按时间比例画成彩色横条。
 *
 * 横轴等分整个时间窗（今天=00:00~现在，全部=首末事件之间），
 * 每段颜色由 ActivityPalette 按应用排名给出，和按小时列表、趋势柱保持一致。
 */
class TimelineStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rectF = RectF()

    private var segments: List<TimelineSegment> = emptyList()
    private var winStart: Long = 0L
    private var winEnd: Long = 0L

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.aw_bar_track)
    }

    /** 更新数据；windowStart/windowEnd 为空时退化成「用数据首尾当横轴」。 */
    fun submit(segments: List<TimelineSegment>, windowStart: Long?, windowEnd: Long?) {
        this.segments = segments
        if (windowStart != null && windowEnd != null && windowEnd > windowStart) {
            winStart = windowStart
            winEnd = windowEnd
        } else if (segments.isNotEmpty()) {
            winStart = segments.minOf { it.startMs }
            winEnd = maxOf(segments.maxOf { it.endMs }, winStart + 60_000L)
        } else {
            winStart = 0L
            winEnd = 0L
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = minOf(h / 2f, 6f)
        rectF.set(0f, 0f, w, h)
        clipPath.rewind()
        clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)

        val saved = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, trackPaint)

        if (winEnd > winStart && segments.isNotEmpty()) {
            val span = (winEnd - winStart).toDouble()
            // 极短的段（几秒）在时间窗以天为单位时不足 1px，给个最小宽度保证可见
            val minSegWidth = 1f
            for (seg in segments) {
                val x0 = ((seg.startMs - winStart) / span * w).toFloat()
                val x1 = ((seg.endMs - winStart) / span * w).toFloat()
                val left = min(x0, x1).coerceIn(0f, w)
                val right = max(x0, x1).coerceIn(0f, w)
                if (right < 0f || left > w) continue
                segPaint.color = ActivityPalette.color(context, seg.colorIndex)
                canvas.drawRect(left, 0f, max(right, min(left + minSegWidth, w)), h, segPaint)
            }
        }
        canvas.restoreToCount(saved)
    }
}
