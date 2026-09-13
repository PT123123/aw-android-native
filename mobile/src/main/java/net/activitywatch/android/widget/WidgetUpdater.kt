package net.activitywatch.android.widget

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 所有桌面小部件的统一刷新入口。
 *
 * 刷新时机：
 * - 系统按 appwidget-provider 的 updatePeriodMillis（30 分钟）广播 APPWIDGET_UPDATE；
 * - 主界面 onResume（打开 App 立即刷新）。
 *
 * 小部件数据只读 UsageStatsManager，不依赖 Rust 服务器/应用进程存活，
 * 因此广播拉起进程后无需启动服务器即可更新。
 */
object WidgetUpdater {
    private const val TAG = "WidgetUpdater"

    /** 小部件点击跳转：MainActivity 收到后路由到对应页面 */
    const val EXTRA_OPEN_TARGET = "open_target"
    const val OPEN_ACTIVITY_OVERVIEW = "activity_overview" // 「今日屏幕使用」→ 活动·概览
    const val OPEN_ACTIVITY_TRENDS = "activity_trends"     // 「日历」→ 活动·趋势

    // 单线程串行执行，避免两个 Provider 同时触发导致重复计算
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 在后台线程执行（Provider.goAsync 之后必须 finish，故由调用方自己包 try/finally） */
    fun execute(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "刷新桌面小部件失败", t)
            }
        }
    }

    /** 刷新全部小部件（内部已捕获异常，可在任意线程调用；无小部件在桌面上时为空操作） */
    fun refreshAllAsync(context: android.content.Context) {
        val app = context.applicationContext
        execute {
            ScreenTimeWidgetProvider.pushUpdate(app)
            CalendarWidgetProvider.pushUpdate(app)
        }
    }
}
