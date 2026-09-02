package net.activitywatch.android.dashboard

import android.content.Context
import android.util.AttributeSet
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import net.activitywatch.android.R
import kotlin.math.max
import kotlin.math.min

/**
 * 时间线色带：把一段时间窗内的活动段按时间比例画成彩色横条。
 *
 * 默认横轴等分整个时间窗（今天=00:00~现在，全部=首末事件之间），
 * 每段颜色由 ActivityPalette 按应用排名给出，和按小时列表、趋势柱保持一致。
 *
 * 接入 [TimelineViewport] 后变为可交互：
 * - 双指捏合缩放、单指平移（多泳道共享同一视口，自动对齐）；
 * - 双击复位到整窗；
 * - 单击命中某段时通过 [onSegmentTap] 回调，由上层弹出详情。
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

    private var viewport: TimelineViewport? = null
    private var viewportListener: (() -> Unit)? = null
    private var onSegmentTap: ((TimelineSegment) -> Unit)? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var lastTouchX = 0f

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.aw_bar_track)

        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    viewport?.zoom(detector.scaleFactor, detector.focusX / max(width.toFloat(), 1f))
                    return true
                }
            },
        )

        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val seg = segmentAt(e.x)
                    if (seg != null) onSegmentTap?.invoke(seg)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    viewport?.reset()
                    return true
                }
            },
        )
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

    /** 接入共享视口：缩放 / 平移会同步到所有绑定同一视口的泳道。 */
    fun setViewport(vp: TimelineViewport?) {
        if (vp === viewport) return
        viewport?.removeListener(viewportListener)
        viewportListener = null
        viewport = vp
        if (vp != null) {
            val l = { invalidate() }
            viewportListener = l
            vp.addListener(l)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        viewport?.removeListener(viewportListener)
        viewport = null
        viewportListener = null
        super.onDetachedFromWindow()
    }

    /** 设置单击命中色块的回调（用于弹详情）。 */
    fun setOnSegmentTap(listener: ((TimelineSegment) -> Unit)?) {
        onSegmentTap = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 捏合与单击 / 双击都交给对应 detector 处理
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    viewport?.panByPx(dx, width.toFloat())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** 把毫秒时刻映射到视图内 x 像素（视口存在时按可视窗口映射）。 */
    private fun xOf(ms: Long): Float {
        val w = width.toFloat()
        if (w <= 0f) return 0f
        val vp = viewport
        val frac = if (vp == null) {
            (ms - winStart).toDouble() / fullSpan()
        } else {
            vp.scrollMs.toDouble() / fullSpan() + (ms - winStart).toDouble() / (fullSpan() * vp.scale)
        }
        return (frac * w).toFloat()
    }

    private fun fullSpan(): Double = max(winEnd - winStart, 1L).toDouble()

    /** 命中测试：返回包含 x 像素处时刻的段；没有则取最近的一段（容差 4px）。 */
    private fun segmentAt(xPx: Float): TimelineSegment? {
        if (segments.isEmpty() || width <= 0) return null
        val ms = viewport?.msAt(xPx, width.toFloat()) ?: run {
            val frac = xPx / width
            winStart + (frac * fullSpan()).toLong()
        }
        var best: TimelineSegment? = null
        var bestDist = Long.MAX_VALUE
        for (seg in segments) {
            if (ms in seg.startMs..seg.endMs) return seg
            val dist = if (ms < seg.startMs) seg.startMs - ms else ms - seg.endMs
            if (dist < bestDist) {
                bestDist = dist
                best = seg
            }
        }
        return if (bestDist <= 4L * fullSpan() / width) best else null
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
            // 极短的段（几秒）在时间窗以天为单位时不足 1px，给个最小宽度保证可见
            val minSegWidth = 1f
            for (seg in segments) {
                val x0 = xOf(seg.startMs)
                val x1 = xOf(seg.endMs)
                val left = min(x0, x1).coerceIn(0f, w)
                val right = max(x0, x1).coerceIn(0f, w)
                if (right <= 0f || left >= w) continue
                segPaint.color = ActivityPalette.color(context, seg.colorIndex)
                canvas.drawRect(left, 0f, max(right, min(left + minSegWidth, w)), h, segPaint)
            }
        }
        canvas.restoreToCount(saved)
    }
}
