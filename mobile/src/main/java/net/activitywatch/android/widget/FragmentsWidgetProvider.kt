package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.activitywatch.android.MainActivity
import net.activitywatch.android.R
import net.activitywatch.android.dashboard.DayFragments
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * 桌面「今日碎片」小部件：把当天的使用记录画成 24 时 × 每时 5 分钟的点阵热力，
 * 底部给出推断的起床 / 入睡时间。点整块 → 活动 · 碎片 Tab（可翻看历史、看启动次数）。
 *
 * 数据与「活动 · 碎片」Tab 同源（[DayFragments]），逐格一致；热力用一张位图推送
 * （RemoteViews 塞不下 288 个 View），尺寸随部件实际大小重算：拖拽缩放会走
 * onAppWidgetOptionsChanged → 按新的像素尺寸重画。
 *
 * 尺寸档位（minWidth/minHeight 为 0 表示还没拿到尺寸，按标准档渲染）：
 * - 微缩（高度 <75dp）：隐藏标题行与统计行，只留点阵；
 * - 标准（高度 ≥115dp）：底部显示「起床 … · 入睡 …」；
 * - 窄宽度（<170dp）：隐藏右上角「更新于」。
 */
class FragmentsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 读 UsageEvents 是 binder 调用，放后台线程；goAsync 保住广播不被提前回收
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
        // 拖拽调整尺寸时按新大小重画点阵位图
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
        private const val MICRO_MAX_HEIGHT_DP = 75
        private const val STATS_MIN_HEIGHT_DP = 115
        private const val HIDE_UPDATED_MAX_WIDTH_DP = 170

        /** 点阵位图上限：RemoteViews 走 Binder（单次事务约 1MB），不能按大部件的原始像素出图 */
        private const val MAX_BITMAP_W = 480
        private const val MAX_BITMAP_H = 260

        private const val COLS = 24
        private const val ROWS = 12

        /** 本小部件专用的 PendingIntent 请求码（屏幕使用 1001、日历 1002，各自唯一） */
        private const val REQ_OPEN = 1003

        private val HM_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

        /** 逐个实例按各自尺寸重算并推送；桌面上没有实例时空操作 */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FragmentsWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val permitted = UsageStatsWatcher.isUsageAllowed(context)
            val todayStart = ScreenTimeStats.startOfDayMs(System.currentTimeMillis())
            val day = if (permitted) DayFragments.scan(context, todayStart) else null

            val updatedText = context.getString(R.string.widget_updated_at, HM_FMT.format(Date()))
            val statsText = when {
                !permitted -> context.getString(R.string.widget_fragments_grant_hint)
                day == null || (day.wakeMs == null && day.sleepMs == null) ->
                    context.getString(R.string.widget_fragments_no_record)
                else -> context.getString(
                    R.string.widget_fragments_stats,
                    day.wakeMs?.let { HM_FMT.format(Date(it)) } ?: "—",
                    day.sleepMs?.let { HM_FMT.format(Date(it)) } ?: "—",
                )
            }

            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val views = buildViews(context, day?.slots, updatedText, statsText, opts)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun buildViews(
            context: Context,
            slots: List<Double>?,
            updatedText: String,
            statsText: String,
            opts: Bundle,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_fragments)
            views.setOnClickPendingIntent(R.id.widget_fragments_root, openAppIntent(context))

            val minHeight = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val micro = minHeight in 1 until MICRO_MAX_HEIGHT_DP
            val showHeader = !micro
            val showStats = minHeight == 0 || minHeight >= STATS_MIN_HEIGHT_DP

            views.setViewVisibility(R.id.ll_frag_header, if (showHeader) View.VISIBLE else View.GONE)
            views.setViewVisibility(
                R.id.tv_frag_updated,
                if (!micro && minWidth in 1 until HIDE_UPDATED_MAX_WIDTH_DP) View.GONE else View.VISIBLE
            )
            views.setViewVisibility(R.id.tv_frag_stats, if (showStats) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.tv_frag_stats, statsText)

            // 点阵位图按「部件实际 dp 尺寸 − 内边距/标题/统计行」推算像素尺寸
            val density = context.resources.displayMetrics.density
            val dpW = if (minWidth > 0) minWidth else 220
            val dpH = if (minHeight > 0) minHeight else 120
            val contentW = dpW - WIDGET_PADDING_H_DP
            val contentH = dpH - WIDGET_PADDING_V_DP -
                (if (showHeader) HEADER_DP else 0) - (if (showStats) STATS_DP else 0)
            views.setImageViewBitmap(
                R.id.iv_frag_heat,
                heatBitmap(context, slots.orEmpty(), (contentW * density).toInt(), (contentH * density).toInt(), density),
            )
            return views
        }

        /**
         * 画点阵热力图：行 = 每小时内的 5 分钟槽（自上而下 :00→:55），列 = 24 小时，
         * 颜色越深该 5 分钟用得越多（与碎片页热力图同一套配色）。
         * 位图够高时在底部标 0/6/12/18 时刻度。
         */
        private fun heatBitmap(
            context: Context,
            slots: List<Double>,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ): Bitmap {
            val w = widthPx.coerceIn(48, MAX_BITMAP_W)
            val h = heightPx.coerceIn(24, MAX_BITMAP_H)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val empty = ContextCompat.getColor(context, R.color.widget_heat_empty)
            val accent = ContextCompat.getColor(context, R.color.widget_accent)
            val levels = intArrayOf(
                empty,
                ColorUtils.setAlphaComponent(accent, 0x59),
                ColorUtils.setAlphaComponent(accent, 0x8C),
                ColorUtils.setAlphaComponent(accent, 0xBF),
                accent,
            )
            val maxSec = slots.maxOrNull() ?: 0.0

            // 够高才留 12dp 给时刻度，否则整张都画点阵
            val axisH = if (h >= (90 * density).toInt()) 12f * density else 0f
            val gridH = h - axisH
            val colW = w / COLS.toFloat()
            val rowH = gridH / ROWS.toFloat()
            val cell = min(colW, rowH)
            val gap = maxOf(1f, cell * 0.18f)
            val radius = (cell - gap) / 2f

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = FloatArray(COLS)
            val cy = FloatArray(ROWS)
            for (c in 0 until COLS) cx[c] = c * colW + colW / 2f
            for (r in 0 until ROWS) cy[r] = r * rowH + rowH / 2f

            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val sec = slots.getOrElse(r * COLS + c) { 0.0 }
                    val level = if (sec <= 0.0 || maxSec <= 0.0) 0
                    else ceil(sec / maxSec * 4).toInt().coerceIn(1, 4)
                    dotPaint.color = levels[level]
                    if (radius >= 1.6f) {
                        canvas.drawCircle(cx[c], cy[r], radius, dotPaint)
                    } else {
                        // 太小就画方块，免得圆点糊成一团
                        canvas.drawRect(cx[c] - cell / 2f, cy[r] - cell / 2f, cx[c] + cell / 2f, cy[r] + cell / 2f, dotPaint)
                    }
                }
            }

            if (axisH > 0f) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ContextCompat.getColor(context, R.color.widget_dim_text)
                    textSize = 8f * density
                }
                val baseline = h - 2f * density
                for (hour in intArrayOf(0, 6, 12, 18)) {
                    val label = hour.toString()
                    canvas.drawText(label, cx[hour] - textPaint.measureText(label) / 2f, baseline, textPaint)
                }
            }
            return bmp
        }

        /** 布局 widget_fragments.xml 的内边距与两行文字高度（dp），用于推算点阵可用高度 */
        private const val WIDGET_PADDING_H_DP = 20
        private const val WIDGET_PADDING_V_DP = 16
        private const val HEADER_DP = 18
        private const val STATS_DP = 17

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQ_OPEN,
                Intent(context, MainActivity::class.java)
                    .setAction(WidgetUpdater.ACTION_OPEN_FRAGMENTS)
                    .putExtra(WidgetUpdater.EXTRA_OPEN_TARGET, WidgetUpdater.OPEN_ACTIVITY_FRAGMENTS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
