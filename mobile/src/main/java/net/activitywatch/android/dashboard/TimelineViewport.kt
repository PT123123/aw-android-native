package net.activitywatch.android.dashboard

import kotlin.math.max

/**
 * 时间线视口：所有泳道共享的缩放 / 平移状态。
 *
 * 横轴永远是「整段数据窗口」[winStart, winEnd]；scale=1 时整窗恰好铺满视图宽度，
 * scale>1 时只显示其中一段（visibleSpan = fullSpan / scale），scrollMs 决定从哪开始。
 * 任一泳道的捏合 / 平移都改这里，再通过监听器让所有泳道一起重绘，保证多轨对齐。
 */
class TimelineViewport(winStart: Long, winEnd: Long) {

    val winStart: Long = winStart
    val winEnd: Long = winEnd
    val fullSpan: Long = max(winEnd - winStart, 1L)

    var scale: Float = 1f
        private set
    var scrollMs: Long = 0L
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (() -> Unit)?) {
        if (l != null) listeners.remove(l)
    }

    private fun emit() {
        for (l in listeners) l()
    }

    /** 当前可见区间起点（毫秒）。 */
    fun visibleStartMs(): Long = winStart + scrollMs

    /** 当前可见区间终点（毫秒）。 */
    fun visibleEndMs(): Long = winStart + scrollMs + (fullSpan.toDouble() / scale).toLong()

    /** 已缩放到最大（=1）时不能再缩小。 */
    fun isFullyZoomedOut(): Boolean = scale <= MIN_SCALE + 1e-3f

    /** 复位到整窗铺满。 */
    fun reset() {
        scale = MIN_SCALE
        scrollMs = 0L
        emit()
    }

    private fun clampScroll() {
        val visibleSpan = (fullSpan.toDouble() / scale).toLong()
        val maxScroll = (fullSpan - visibleSpan).coerceAtLeast(0L)
        scrollMs = scrollMs.coerceIn(0L, maxScroll)
    }

    /**
     * 以视图内 focusFrac（0..1，从左往右）处的时间为锚点缩放 factor 倍。
     * factor>1 放大、<1 缩小；锚点对应的时刻在缩放前后留在屏幕同一位置。
     */
    fun zoom(factor: Float, focusFrac: Float) {
        if (factor <= 0f) return
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == scale) return
        val oldVisibleSpan = fullSpan / scale
        val focusMs = visibleStartMs() + (focusFrac * oldVisibleSpan).toLong()
        scale = newScale
        val newVisibleSpan = fullSpan / scale
        scrollMs = (focusMs - winStart - focusFrac * newVisibleSpan).toLong()
        clampScroll()
        emit()
    }

    /** 单指平移：dxPx 为手指在视图宽度 viewWidth 内的位移（向右为正）。 */
    fun panByPx(dxPx: Float, viewWidth: Float) {
        if (viewWidth <= 0f || scale <= MIN_SCALE + 1e-3f) return
        // 手指右移（dxPx>0）→ 内容右移 → 显示更早的时刻 → scrollMs 减小
        scrollMs -= (dxPx * fullSpan / (scale * viewWidth)).toLong()
        clampScroll()
        emit()
    }

    /** 把视图内 x 像素换算成对应的毫秒时刻（需传入当前视图宽度）。 */
    fun msAt(xPx: Float, viewWidth: Float): Long {
        if (viewWidth <= 0f) return winStart
        val frac = scrollMs.toFloat() / fullSpan + xPx / (scale * viewWidth)
        return winStart + (frac * fullSpan).toLong()
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 240f
    }
}
