package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import net.activitywatch.android.MainActivity
import net.activitywatch.android.R
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * 「日历」小部件：当月月历（周起始日跟随系统地区设置），今天用主题色圆点高亮，
 * 点击打开主界面。纯 RemoteViews 绘制，逐行 addView 生成周行。
 *
 * 用 java.util.Calendar 而非 ThreeTenBP：项目未全局调 AndroidThreeTen.init()，
 * LocalDate.now() 依赖的时区库在该状态下不可用。
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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
        // 拖拽调整尺寸后重绘（网格按周生成，横向拉伸自然铺开）
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
        /** 计算并推送当前桌面上所有「日历」小部件；没有实例时空操作 */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, buildViews(context))
        }

        private val gridCellIds = intArrayOf(
            R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3,
            R.id.cell_4, R.id.cell_5, R.id.cell_6
        )

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            views.setOnClickPendingIntent(R.id.widget_calendar_root, openAppIntent(context))

            val now = Calendar.getInstance()
            views.setTextViewText(
                R.id.tv_cal_title,
                context.getString(R.string.widget_calendar_title, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
            )

            val cal = now.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val firstDow = cal.get(Calendar.DAY_OF_WEEK)              // 本月 1 号是周几
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            // 周起始日跟随系统设置（Calendar.SUNDAY=1..SATURDAY=7）
            val weekStart = Calendar.getInstance(Locale.getDefault()).firstDayOfWeek
            val leadingBlanks = (firstDow - weekStart + 7) % 7

            val primary = ContextCompat.getColor(context, R.color.widget_text_primary)
            val dim = ContextCompat.getColor(context, R.color.widget_dim_text)
            val onAccent = ContextCompat.getColor(context, R.color.widget_text_on_accent)
            val secondary = ContextCompat.getColor(context, R.color.widget_text_secondary)

            // 表头：从周起始日连排 7 个短星期名（中文取「周一」的最后一位 → 一/二/…，其他 locale 保持原样）
            val symbols = DateFormatSymbols(Locale.getDefault()).shortWeekdays
            val headerRow = RemoteViews(context.packageName, R.layout.widget_calendar_row_header)
            for (i in 0 until 7) {
                val dowIndex = ((weekStart - 1) + i) % 7 + 1           // 映射回 Calendar 的 SUNDAY=1..SATURDAY=7
                val name = symbols.getOrNull(dowIndex)?.removePrefix("周").orEmpty()
                headerRow.setTextViewText(gridCellIds[i], name)
                headerRow.setTextColor(gridCellIds[i], secondary)
            }
            views.removeAllViews(R.id.ll_cal_header)
            views.addView(R.id.ll_cal_header, headerRow)

            // 日期网格：整周为单位补空位，跨月日期置灰
            views.removeAllViews(R.id.ll_cal_grid)
            var day = 1 - leadingBlanks
            while (day <= daysInMonth) {
                val row = RemoteViews(context.packageName, R.layout.widget_calendar_row)
                for (i in 0 until 7) {
                    val cellId = gridCellIds[i]
                    val cellDay = day + i
                    if (cellDay < 1 || cellDay > daysInMonth) {
                        row.setTextViewText(cellId, "")
                        row.setTextColor(cellId, dim)
                        row.setInt(cellId, "setBackgroundResource", 0)
                        continue
                    }
                    row.setTextViewText(cellId, cellDay.toString())
                    val isToday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                        now.get(Calendar.MONTH) == cal.get(Calendar.MONTH) &&
                        now.get(Calendar.DAY_OF_MONTH) == cellDay
                    if (isToday) {
                        row.setTextColor(cellId, onAccent)
                        row.setInt(cellId, "setBackgroundResource", R.drawable.widget_today_bg)
                    } else {
                        row.setTextColor(cellId, primary)
                        row.setInt(cellId, "setBackgroundResource", 0)
                    }
                }
                views.addView(R.id.ll_cal_grid, row)
                day += 7
            }
            return views
        }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java)
                    .putExtra(WidgetUpdater.EXTRA_OPEN_TARGET, WidgetUpdater.OPEN_ACTIVITY_TRENDS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
