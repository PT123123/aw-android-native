package net.activitywatch.android

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * 支持在左侧热区内右滑打开抽屉的 DrawerLayout。
 * 系统控件只响应贴着屏幕左缘约 20dp 的拖动，这里把检测范围扩大到可配置的
 * 屏宽比例（[edgeZoneRatio]），检测到明确的右滑后直接调用 openDrawer。
 * 设为 0 时关闭右滑开抽屉（汉堡按钮仍可打开）。
 */
class EdgeDragDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DrawerLayout(context, attrs, defStyleAttr) {

    /** 热区宽度占屏幕宽度的比例，0 表示关闭右滑开抽屉 */
    var edgeZoneRatio: Float = 1f / 3f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyLockMode()
        }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracking = false
    private var startX = 0f
    private var startY = 0f

    init {
        applyLockMode()
    }

    private fun applyLockMode() {
        // 关闭热区时连同系统自带的 20dp 边缘拖动一起禁用；
        // LOCK_MODE_LOCKED_CLOSED 只拦手势，不影响程序调用 openDrawer
        setDrawerLockMode(
            if (edgeZoneRatio > 0f) LOCK_MODE_UNLOCKED else LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START,
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        detectZoneSwipe(ev)
        return super.onInterceptTouchEvent(ev)
    }

    private fun detectZoneSwipe(ev: MotionEvent) {
        if (edgeZoneRatio <= 0f) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                tracking = ev.x <= width * edgeZoneRatio && !isDrawerOpen(GravityCompat.START)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (dx > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 2) {
                    tracking = false
                    openDrawer(GravityCompat.START)
                } else if (Math.abs(dy) > touchSlop * 2 && Math.abs(dy) > Math.abs(dx)) {
                    // 判定为纵向滚动意图，放弃跟踪
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }
}
