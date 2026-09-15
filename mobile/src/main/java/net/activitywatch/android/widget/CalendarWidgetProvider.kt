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
 * - 档位（任一方向 < [COMPACT_MAX_DP] 为紧凑档，否则标准档）只决定内边距
 *   （5/10dp）、标题文案（「9月」/「2026年9月」）与今天高亮形状；尺寸未知
 *   （澎湃OS/MIUI 可能不上报，恒为 0）按 2x2 兜底走紧凑档。
 * - 字号不分档，一律按实际尺寸以 **dp** 反推（sp 会被 fontScale 放大：大字体下
 *   两位数折行、个位被裁）：标题约占高度 14%、表头 10%（大部件里标题自然放大填充
 *   空白，小部件里缩到下限），其余全部留给日期行，保证每个日期都完整显示。
 * - 日期网格与日期行都用 weight 分配高度（见 widget_calendar.xml / widget_calendar_row.xml），
 *   所以「上报尺寸比实际可视区偏大」时最多是字略小，不会把最后一行挤到部件外面。
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
         * 紧凑档阈值（dp）：启动器按 cell 换算的可用区，2x2 约 110-165dp（澎湃OS
         * 5 列网格偏大）、3x3 约 230-250dp，取 170 作分界。字号已改为按实际尺寸
         * 撑满，档位只影响内边距/标题文案/高亮形状，误判档位也不会溢出。
         */
        private const val COMPACT_MAX_DP = 170

        /** 紧凑档 / 标准档内边距（dp）。必须与 [measureTexts] 的可用区计算保持一致 */
        private const val PAD_H_COMPACT = 5
        private const val PAD_V_COMPACT = 5
        private const val PAD_H_STD = 10
        private const val PAD_V_STD = 8

        /** 一组字号（单位 dp，不受系统字体缩放影响） */
        private class Texts(val title: Float, val header: Float, val day: Float)

        /** 单行文本「高度 / 字号」经验比（includeFontPadding 已关，CJK 略矮于数字） */
        private const val LINE_H = 1.2

        /** 日期行「行高 / 字号」：MiSans 等字体行盒明显高于 Roboto，估小了下半会被裁 */
        private const val DAY_LINE_H = 1.32

        /** 两位数占宽「em / 字号」：2 字 × 0.6em（数字字宽取宽值，防宽字体折行） */
        private const val TWO_DIGIT_EM = 1.2

        /** 标题行与表头行之间的固定间距（2dp + 1dp 的 layout_marginTop） */
        private const val GAP = 3.0

        private const val MIN_DAY = 6.5
        private const val MAX_DAY = 40.0

        /**
         * 按实例尺寸反推字号，单位 **dp**。
         *
         * 为什么不用 sp：sp 会被系统字体缩放（fontScale）放大，而格子宽度是按 dp 死的，
         * 大字体下两位数会折行（个位被裁）、行数会溢出。dp 与格子同尺度，可精确计算。
         *
         * 高度按比例分配，而不是让标题/表头跟着日期字号浮动——后者在「宽而矮」的
         * 3x2 里会让标题挤掉日期行的空间、最后一行被裁：
         * - 标题 ≈ 14%、表头 ≈ 10%（各自夹在上下限内，大部件里标题自然放大填充空白，
         *   小部件里缩到下限），余下全部给日期行；
         * - 再压 6% 余量：启动器上报的 min 尺寸可能大于实际可视区（cell 间距、圆角内缩
         *   都算进去了），宁可整体小一档，也不能让最后一行看不见；
         * - 宽度上限保证两位数不折行（横向才是真正的硬约束）。
         * 字号下限 6.5dp（再小看不清）、上限 40dp。
         */
        private fun measureTexts(minWidth: Int, minHeight: Int, weekRows: Int, padH: Int, padV: Int): Texts {
            val wAvail = ((if (minWidth > 0) minWidth else 110) - 2 * padH).coerceAtLeast(42).toDouble()
            val hRaw = ((if (minHeight > 0) minHeight else 110) - 2 * padV).coerceAtLeast(40).toDouble()
            val hAvail = (hRaw - maxOf(3.5, hRaw * 0.06)).coerceAtLeast(30.0)

            val dayByWidth = wAvail / 7.0 / (TWO_DIGIT_EM * 1.05)
            val titleH = (hAvail * 0.14).coerceIn(11.0, 22.0)
            val headerH = (hAvail * 0.10).coerceIn(9.0, 14.0)
            val rowsH = (hAvail - titleH - headerH - GAP).coerceAtLeast(0.0)
            val day = minOf(dayByWidth, rowsH / (weekRows * DAY_LINE_H)).coerceIn(MIN_DAY, MAX_DAY)
            return Texts(
                title = (titleH / LINE_H).toFloat(),
                header = (headerH / LINE_H).toFloat(),
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
            // 档位判断：任一方向 < COMPACT_MAX_DP（2x2 级别）走紧凑档；尺寸未知
            // （澎湃OS/MIUI 可能不上报，恒为 0）时按 2x2 兜底走紧凑档。
            // 档位只影响内边距、标题文案与高亮形状——字号不分档，一律按实际
            // 尺寸以 dp 反推并撑满，误判档位也不会溢出。
            val compact = (minWidth in 1 until COMPACT_MAX_DP) ||
                (minHeight in 1 until COMPACT_MAX_DP) ||
                (minWidth <= 0 && minHeight <= 0)

            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            views.setOnClickPendingIntent(R.id.widget_calendar_root, openAppIntent(context))

            // 内边距：RemoteViews 覆盖不了 layout 里的 padding，只能代码设
            val padH = if (compact) PAD_H_COMPACT else PAD_H_STD
            val padV = if (compact) PAD_V_COMPACT else PAD_V_STD
            views.setViewPadding(
                R.id.widget_calendar_root,
                dp(context, padH), dp(context, padV), dp(context, padH), dp(context, padV)
            )

            val now = Calendar.getInstance()

            // 先算当月网格结构（周数随月份 4-6 行浮动），字号才能精确撑满高度
            val cal = now.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val firstDow = cal.get(Calendar.DAY_OF_WEEK)              // 本月 1 号是周几
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            // 周起始日跟随系统设置（Calendar.SUNDAY=1..SATURDAY=7）
            val weekStart = Calendar.getInstance(Locale.getDefault()).firstDayOfWeek
            val leadingBlanks = (firstDow - weekStart + 7) % 7
            val weekRows = (leadingBlanks + daysInMonth + 6) / 7

            // 字号按实际尺寸反推（dp，不受 fontScale 放大），按当月实际周数撑满高度
            val texts = measureTexts(minWidth, minHeight, weekRows, padH, padV)
            val unit = TypedValue.COMPLEX_UNIT_DIP

            if (compact) {
                // 2x2 里「2026年9月」太长，缩成「9月」
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

            val primary = ContextCompat.getColor(context, R.color.widget_text_primary)
            val dim = ContextCompat.getColor(context, R.color.widget_dim_text)
            val onAccent = ContextCompat.getColor(context, R.color.widget_text_on_accent)
            val secondary = ContextCompat.getColor(context, R.color.widget_text_secondary)

            // 行布局：日期行 weight=1（等分网格高度），表头行 wrap_content
            val headerRes = R.layout.widget_calendar_row_header
            val rowRes = R.layout.widget_calendar_row
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
