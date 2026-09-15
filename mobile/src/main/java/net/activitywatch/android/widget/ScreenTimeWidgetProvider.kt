package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import net.activitywatch.android.MainActivity
import net.activitywatch.android.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「今日屏幕使用」小部件：显示今日总使用时长 + 常用应用图例，点击进 活动·概览。
 *
 * 尺寸自适应（onAppWidgetOptionsChanged + onUpdate 都会重算）：
 * - 微缩（高度 <85dp，约 1 行）：隐藏标题行，只留大字时长；
 * - 紧凑（高度 <115dp）或窄宽度（<140dp）：无图例；
 * - 宽幅（宽度 ≥320dp，约 4 列）：top5 图例；其余 top3；
 * - 窄宽度（<170dp）：隐藏右上角「更新于」。
 * 字号不写死：布局启用 TextView autoSizeTextType，由系统按实际空间自动缩放，
 * 缩到下限仍放不下才走 ellipsize 兜底。
 */
class ScreenTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // UsageStats 查询是 binder 调用，放后台线程；goAsync 保住广播不被提前回收
        val pendingResult = goAsync()
        WidgetUpdater.execute {
            try {
                pushUpdate(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // 拖拽调整尺寸时按新大小重算档位
        val pendingResult = goAsync()
        WidgetUpdater.execute {
            try {
                pushUpdate(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * 档位（按系统上报的 dp 尺寸）：
         * - 微缩：高度 <85dp（约 1 行）→ 隐藏标题行，只留大字时长（时长字号由 autofit 自适应）；
         * - 紧凑：高度 <115dp 或宽度 <140dp → 无图例；
         * - 宽幅：宽度 ≥320dp → top5 图例；其余 → top3。
         * 字号不写死：布局启用 TextView autoSizeTextType，按实际空间自动缩放。
         */
        private const val MICRO_MAX_HEIGHT_DP = 85
        private const val COMPACT_MAX_HEIGHT_DP = 115
        private const val NARROW_MAX_WIDTH_DP = 140
        private const val WIDE_MIN_WIDTH_DP = 320
        private const val HIDE_UPDATED_MAX_WIDTH_DP = 170

        private val ROW_IDS = intArrayOf(
            R.id.ll_legend_0, R.id.ll_legend_1, R.id.ll_legend_2, R.id.ll_legend_3, R.id.ll_legend_4
        )
        private val TEXT_IDS = intArrayOf(
            R.id.tv_legend_0, R.id.tv_legend_1, R.id.tv_legend_2, R.id.tv_legend_3, R.id.tv_legend_4
        )

        /** 逐个实例按各自尺寸重算并推送；桌面上没有实例时空操作 */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ScreenTimeWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val result = ScreenTimeStats.today(context)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val updatedText = context.getString(R.string.widget_updated_at, timeFmt.format(Date()))

            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val views = buildViews(context, result, updatedText, opts)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun buildViews(
            context: Context,
            result: ScreenTimeStats.Result,
            updatedText: String,
            opts: Bundle,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_screen_time)
            views.setOnClickPendingIntent(R.id.widget_screen_time_root, openAppIntent(context))

            val minHeight = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            // 高度为 0 表示尚未拿到尺寸（初次放置前的占位），按标准档渲染
            val micro = minHeight in 1 until MICRO_MAX_HEIGHT_DP
            val compactHeight = minHeight in 1 until COMPACT_MAX_HEIGHT_DP
            val narrow = minWidth in 1 until NARROW_MAX_WIDTH_DP
            val wide = minWidth >= WIDE_MIN_WIDTH_DP
            val legendCount = when {
                compactHeight || narrow -> 0
                wide -> 5
                else -> 3
            }
            // 微缩档：整个标题行隐藏，只留大字时长（字号由 autofit 自适应）
            views.setViewVisibility(R.id.ll_header, if (micro) View.GONE else View.VISIBLE)
            views.setViewVisibility(
                R.id.tv_widget_updated,
                if (!micro && minWidth in 1 until HIDE_UPDATED_MAX_WIDTH_DP) View.GONE else View.VISIBLE
            )

            if (!result.permitted) {
                views.setTextViewText(R.id.tv_widget_duration, context.getString(R.string.widget_screen_time_no_permission))
                views.setTextViewText(R.id.tv_widget_updated, "")
                views.setViewVisibility(R.id.ll_legend_0, View.VISIBLE)
                views.setTextViewText(R.id.tv_legend_0, context.getString(R.string.widget_screen_time_grant_hint))
                for (i in 1 until ROW_IDS.size) views.setViewVisibility(ROW_IDS[i], View.GONE)
                return views
            }

            views.setTextViewText(R.id.tv_widget_duration, ScreenTimeStats.formatDuration(result.totalMs))
            views.setTextViewText(R.id.tv_widget_updated, updatedText)

            for (i in ROW_IDS.indices) {
                val app = result.topApps.getOrNull(i)
                if (i < legendCount && app != null) {
                    views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                    views.setTextViewText(
                        TEXT_IDS[i],
                        "${app.label} · ${ScreenTimeStats.formatDuration(app.ms)}"
                    )
                } else {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }
            return views
        }

        /** 本小部件专用的 PendingIntent 请求码（与日历、待办通知区分开） */
        private const val REQ_OPEN = 1001

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQ_OPEN,
                Intent(context, MainActivity::class.java)
                    .setAction(WidgetUpdater.ACTION_OPEN_SCREEN_TIME)
                    .putExtra(WidgetUpdater.EXTRA_OPEN_TARGET, WidgetUpdater.OPEN_ACTIVITY_OVERVIEW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
