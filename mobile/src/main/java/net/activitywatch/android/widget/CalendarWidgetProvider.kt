package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
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
 * 尺寸分档（onAppWidgetOptionsChanged / onUpdate 都会重算）：
 * - 紧凑档（任一方向 < [COMPACT_MAX_DP]，即 2x2 级别）：换用紧凑行布局
 *   （无 minHeight、0.5dp 行距）、内边距收到 6/5dp、标题缩成「9月」11sp、
 *   日期 9sp——6 周（42 格）合计约 92dp，能完整塞进 110dp 的 2x2 区域；
 * - 标准档（3x3 及以上）：维持原字号与内边距。
 * 字号统一走 setTextViewTextSize（布局里的 textSize 只是预览兜底）。
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
        // 拖拽调整尺寸后按新尺寸重绘（换档位 / 重排字号）
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
         * 紧凑档阈值（dp）：启动器按 cell 换算的可用区，2x2 约 110dp、3x3 约 250dp，
         * 取 150 作分界能可靠区分两者（同时兼容 2x1 / 4x2 这类非方形尺寸）。
         */
        private const val COMPACT_MAX_DP = 150

        /** 紧凑档内边距（dp）。必须与 [measureCompact] 的可用区计算保持一致 */
        private const val PAD_H_COMPACT = 5
        private const val PAD_V_COMPACT = 5

        /** 一组字号（紧凑档单位为 dp，标准档为 sp） */
        private class Texts(val title: Float, val header: Float, val day: Float)

        /** 标准档（3x3 及以上）：空间充裕，沿用 sp，跟随系统字体缩放无妨 */
        private val STANDARD_TEXTS = Texts(14f, 10f, 12f)

        /**
         * 按实例尺寸反推紧凑档字号，单位 **dp**。
         *
         * 为什么不用 sp：sp 会被系统字体缩放（fontScale）放大，而格子宽度是按 dp 死的。
         * 本机实测 fontScale=1.45 时，9sp 实渲 ≈13dp、「10」这种两位数宽 ≈14.4dp，
         * 而 2x2 的格子只有 ≈14dp —— 刚好差一点，于是两位数被迫折行、下半被裁掉
         * （表现为「10」的 0 掉到 1 下面、14 的 4 / 18 的 8 / 20 的 0 看不见）。
         * 改用 dp 后字号与格子宽度同尺度，可精确计算。
         *
         * 横向：7 列等宽，两位数占 2×0.56em（Roboto 数字字宽），两侧留 4% 余量；
         * 纵向：6 周 × 行高 + 标题 + 表头 + 两处行距（行高按 1.18em 估）。
         * 取两者较小值；上限 10dp（高度放不下更大的）、下限 6.5dp（再小看不清）。
         */
        private fun measureCompact(minWidth: Int, minHeight: Int): Texts {
            val w = (if (minWidth > 0) minWidth else 110) - 2 * PAD_H_COMPACT
            val h = (if (minHeight > 0) minHeight else 110) - 2 * PAD_V_COMPACT
            val cellW = w.coerceAtLeast(42) / 7.0
            val byWidth = cellW / (2 * 0.56 * 1.04)
            val byHeight = (h.coerceAtLeast(60) - 9.0 - 10.0 - 4.0) / 6.0 / 1.18
            val day = minOf(byWidth, byHeight, 10.0).coerceAtLeast(6.5)
            return Texts(
                title = minOf(day + 2.0, 11.5).toFloat(),
                header = minOf(day, 8.5).toFloat(),
                day = day.toFloat(),
            )
        }

        /** 本小部件专用的 PendingIntent 请求码（与屏幕使用小部件、待办通知区分开） */
        private const val REQ_OPEN = 1002

        /** 计算并推送当前桌面上所有「日历」小部件；没有实例时空操作 */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            // 逐实例取尺寸：同一桌面上可能同时存在 2x2 与 3x3 两个日历
            for (id in ids) {
                mgr.updateAppWidget(id, buildViews(context, mgr.getAppWidgetOptions(id)))
            }
        }

        private val gridCellIds = intArrayOf(
            R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3,
            R.id.cell_4, R.id.cell_5, R.id.cell_6
        )

        private fun buildViews(context: Context, opts: Bundle): RemoteViews {
            val minWidth = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            // 尺寸为 0 表示尚未拿到实际尺寸（初次放置前的占位），按标准档渲染
            val compact = (minWidth in 1 until COMPACT_MAX_DP) || (minHeight in 1 until COMPACT_MAX_DP)

            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            views.setOnClickPendingIntent(R.id.widget_calendar_root, openAppIntent(context))

            // 内边距：RemoteViews 覆盖不了 layout 里的 padding，只能代码设
            val padH = dp(context, if (compact) PAD_H_COMPACT else 10)
            val padV = dp(context, if (compact) PAD_V_COMPACT else 8)
            views.setViewPadding(R.id.widget_calendar_root, padH, padV, padH, padV)

            // 紧凑档字号走 dp（不受 fontScale 放大），标准档走 sp
            val texts = if (compact) measureCompact(minWidth, minHeight) else STANDARD_TEXTS
            val unit = if (compact) TypedValue.COMPLEX_UNIT_DIP else TypedValue.COMPLEX_UNIT_SP

            val now = Calendar.getInstance()
            if (compact) {
                // 2x2 里「2026年9月」一行太占高度，缩成「9月」并把字降一档
                views.setTextViewText(
                    R.id.tv_cal_title,
                    context.getString(R.string.widget_calendar_title_short, now.get(Calendar.MONTH) + 1)
                )
            } else {
                views.setTextViewText(
                    R.id.tv_cal_title,
                    context.getString(
                        R.string.widget_calendar_title,
                        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1
                    )
                )
            }
            views.setTextViewTextSize(R.id.tv_cal_title, unit, texts.title)

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

            val headerRes = if (compact) R.layout.widget_calendar_row_header_compact
            else R.layout.widget_calendar_row_header
            val rowRes = if (compact) R.layout.widget_calendar_row_compact
            else R.layout.widget_calendar_row
            val daySize = texts.day
            val headSize = texts.header
            // 紧凑档格子是扁的，oval 会被拉成椭圆，换成小圆角方块
            val todayBg = if (compact) R.drawable.widget_today_bg_compact else R.drawable.widget_today_bg

            // 表头：从周起始日连排 7 个短星期名（中文取「周一」的最后一位 → 一/二/…，其他 locale 保持原样）
            val symbols = DateFormatSymbols(Locale.getDefault()).shortWeekdays
            val headerRow = RemoteViews(context.packageName, headerRes)
            for (i in 0 until 7) {
                val dowIndex = ((weekStart - 1) + i) % 7 + 1           // 映射回 Calendar 的 SUNDAY=1..SATURDAY=7
                val name = symbols.getOrNull(dowIndex)?.removePrefix("周").orEmpty()
                headerRow.setTextViewText(gridCellIds[i], name)
                headerRow.setTextViewTextSize(gridCellIds[i], unit, headSize)
                headerRow.setTextColor(gridCellIds[i], secondary)
            }
            views.removeAllViews(R.id.ll_cal_header)
            views.addView(R.id.ll_cal_header, headerRow)

            // 日期网格：整周为单位补空位，跨月日期置灰
            views.removeAllViews(R.id.ll_cal_grid)
            var day = 1 - leadingBlanks
            while (day <= daysInMonth) {
                val row = RemoteViews(context.packageName, rowRes)
                for (i in 0 until 7) {
                    val cellId = gridCellIds[i]
                    val cellDay = day + i
                    row.setTextViewTextSize(cellId, unit, daySize)
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
                        row.setInt(cellId, "setBackgroundResource", todayBg)
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

        private fun dp(context: Context, v: Int): Int =
            (v * context.resources.displayMetrics.density).toInt()

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQ_OPEN,
                Intent(context, MainActivity::class.java)
                    .setAction(WidgetUpdater.ACTION_OPEN_CALENDAR)
                    .putExtra(WidgetUpdater.EXTRA_OPEN_TARGET, WidgetUpdater.OPEN_ACTIVITY_TRENDS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
