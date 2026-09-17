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
 * 纵向 **自下而上 :00→:55**（最底行 :00、最顶行 :55），底部给出推断的起床 / 入睡时间。
 * 点整块 → 活动 · 碎片 Tab（可翻看历史、看启动次数）。
 *
 * 数据与「活动 · 碎片」Tab 同源（[DayFragments]），逐格一致；热力用一张位图推送
 * （RemoteViews 塞不下 288 个 View），尺寸随部件实际大小重算：拖拽缩放会走
 * onAppWidgetOptionsChanged → 按新的像素尺寸重画。
 *
 * 尺寸档位（minWidth/minHeight 为 0 表示还没拿到尺寸，按标准档渲染）：
 * - 微缩（高度 <75dp）：隐藏标题行与统计行，只留点阵；
 * - 窄宽（宽度 <150dp，即 2 格宽）：标题让位——标题 +「更新于」两段文字挤不下，
 *   而「更新于」是点阵本身读不出来的信息，所以收标题（INVISIBLE 占位）、保留更新时间；
 *   底部起床 / 入睡换成两行排法，免得单行被 ellipsize 吃掉后半段；
 * - 其余尺寸：底部常驻一行「起床 … · 入睡 …」，**不按高度分档**——默认落位 3×2 只有
 *   2 格高，按高度卡会让这一行永久看不见。
 *
 * 作息推断不出来时写「没起 / 没睡」而不是留空：早上还没睡过、久没碰手机都不是「没内容」。
 *
 * 刻度同样自适应，空间不够就自动省掉、把位置让给点阵：
 * - 底部小时刻度（0/6/12/18）要位图高度 ≥90dp；
 * - 左侧分钟刻度（:15/:45）还要每行 ≥6dp、且留出刻度栏后每列仍 ≥3dp。
 *
 * 最小尺寸 2×2（110×110dp）：24 列点阵在该宽度下每列约 3.7dp，是仍能分辨的下限。
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

        /** 2 格宽（<150dp）视为窄宽档：统计行、标题都收掉，留给点阵与常驻的「更新于」 */
        private const val NARROW_MAX_WIDTH_DP = 150

        /** 点阵位图上限：RemoteViews 走 Binder（单次事务约 1MB），不能按大部件的原始像素出图 */
        private const val MAX_BITMAP_W = 480
        private const val MAX_BITMAP_H = 260

        private const val COLS = 24
        private const val ROWS = 12

        /** 纵轴分钟刻度：数据行（0 = :00）× 文案；行自下而上，故 :45 在上、:15 在下 */
        private val Y_TICKS = listOf(3 to ":15", 9 to ":45")

        /** 纵轴刻度栏占位后每列仍要 ≥3dp 才画刻度，否则点阵被挤得太小反而看不清 */
        private const val MIN_COL_DP = 3f

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
            // 推断不出作息也照样写出来：「早上还没睡过」「久没碰手机」都不是没内容，留空会被当成部件坏了
            val wakeText = day?.wakeMs?.let { HM_FMT.format(Date(it)) }
                ?: context.getString(R.string.widget_fragments_no_wake)
            val sleepText = day?.sleepMs?.let { HM_FMT.format(Date(it)) }
                ?: context.getString(R.string.widget_fragments_no_sleep)
            // 两种排法：宽部件一行放得下，2 格宽放不下就换成「起床 …\n入睡 …」两行
            val statsText: String
            val statsTextStacked: String
            when {
                !permitted -> {
                    statsText = context.getString(R.string.widget_fragments_grant_hint)
                    statsTextStacked = statsText
                }
                // 扫不到数据（极端情况）：这一行也照样占住，别让底部整块空掉
                day == null -> {
                    statsText = context.getString(R.string.widget_fragments_no_record)
                    statsTextStacked = statsText
                }
                else -> {
                    statsText = context.getString(R.string.widget_fragments_stats, wakeText, sleepText)
                    statsTextStacked =
                        context.getString(R.string.widget_fragments_stats_stacked, wakeText, sleepText)
                }
            }

            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val views = buildViews(context, day?.slots, updatedText, statsText, statsTextStacked, opts)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun buildViews(
            context: Context,
            slots: List<Double>?,
            updatedText: String,
            statsText: String,
            statsTextStacked: String,
            opts: Bundle,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_fragments)
            views.setOnClickPendingIntent(R.id.widget_fragments_root, openAppIntent(context))

            val minHeight = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val minWidth = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val micro = minHeight in 1 until MICRO_MAX_HEIGHT_DP
            val narrow = minWidth in 1 until NARROW_MAX_WIDTH_DP
            val showHeader = !micro
            // 统计行不按高度分档、也不因窄宽收掉：默认落位 3×2 只有 2 格高，按高度卡会把
            // 起床 / 入睡永久藏起来；2 格宽单行放不下就折成两行。只有微缩档（整行收起）不显示。
            val showStats = !micro && statsText.isNotEmpty()

            views.setViewVisibility(R.id.ll_frag_header, if (showHeader) View.VISIBLE else View.GONE)
            // 窄宽档（2×2）：标题让位给「更新于」。标题用 INVISIBLE 而非 GONE——
            // 它仍占住带权重的空白，把「更新于」顶在右上角原处，收缩时既不移动也不消失。
            views.setViewVisibility(R.id.tv_frag_title, if (narrow) View.INVISIBLE else View.VISIBLE)
            views.setViewVisibility(R.id.tv_frag_updated, if (showHeader) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.tv_frag_stats, if (showStats) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.tv_frag_updated, updatedText)
            // 2 格宽用两行排法，免得单行被 ellipsize 吃掉「入睡 23:40」那一半
            views.setTextViewText(R.id.tv_frag_stats, if (narrow) statsTextStacked else statsText)

            // 点阵位图按「部件实际 dp 尺寸 − 内边距/标题/统计行」推算像素尺寸
            val density = context.resources.displayMetrics.density
            val dpW = if (minWidth > 0) minWidth else 220
            val dpH = if (minHeight > 0) minHeight else 120
            val contentW = dpW - WIDGET_PADDING_H_DP
            val statsDp = if (narrow) STATS_DP_STACKED else STATS_DP
            val contentH = dpH - WIDGET_PADDING_V_DP -
                (if (showHeader) HEADER_DP else 0) - (if (showStats) statsDp else 0)
            views.setImageViewBitmap(
                R.id.iv_frag_heat,
                heatBitmap(context, slots.orEmpty(), (contentW * density).toInt(), (contentH * density).toInt(), density),
            )
            return views
        }

        /**
         * 画点阵热力图：列 = 24 小时，行 = 每小时内的 5 分钟槽 —— **自下而上 :00→:55**，
         * 即 :00 在最底行、:55 在最顶行（与应用内「使用碎片」一致）。颜色越深该 5 分钟用得越多。
         * 空间够时底部标 0/6/12/18 小时刻度、左侧标 :15/:45 分钟刻度，不够就自动省掉刻度栏。
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

            // 够高才留 12dp 给小时刻度，否则整张都画点阵
            val axisH = if (h >= (90 * density).toInt()) 12f * density else 0f
            val gridH = h - axisH

            // 刻度字号跟着格子大小走（6~9dp），位图越小字越小
            val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.widget_dim_text)
                textSize = (min(w / COLS.toFloat(), gridH / ROWS) * 0.95f)
                    .coerceIn(6f * density, 9f * density)
            }
            // 纵轴刻度栏：行太矮、或留完栏每列不足 3dp 就不留（自适应，空间让给点阵）
            val yAxisW = Y_TICKS.maxOf { tickPaint.measureText(it.second) }
            val gutter = if (gridH / ROWS >= 6f * density &&
                w - (yAxisW + 2f * density) >= COLS * MIN_COL_DP * density
            ) {
                yAxisW + 2f * density
            } else {
                0f
            }

            val colW = (w - gutter) / COLS.toFloat()
            val rowH = gridH / ROWS.toFloat()
            val cell = min(colW, rowH)
            val gap = maxOf(1f, cell * 0.18f)
            val radius = (cell - gap) / 2f

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = FloatArray(COLS)
            val cy = FloatArray(ROWS)
            for (c in 0 until COLS) cx[c] = gutter + c * colW + colW / 2f
            for (d in 0 until ROWS) cy[d] = d * rowH + rowH / 2f

            // 显示行 d（0 = 最顶）对应数据行 ROWS-1-d（0 = :00），所以 :00 落在最底
            for (d in 0 until ROWS) {
                val r = ROWS - 1 - d
                for (c in 0 until COLS) {
                    val sec = slots.getOrElse(r * COLS + c) { 0.0 }
                    val level = if (sec <= 0.0 || maxSec <= 0.0) 0
                    else ceil(sec / maxSec * 4).toInt().coerceIn(1, 4)
                    dotPaint.color = levels[level]
                    if (radius >= 1.6f) {
                        canvas.drawCircle(cx[c], cy[d], radius, dotPaint)
                    } else {
                        // 太小就画方块，免得圆点糊成一团
                        canvas.drawRect(cx[c] - cell / 2f, cy[d] - cell / 2f, cx[c] + cell / 2f, cy[d] + cell / 2f, dotPaint)
                    }
                }
            }

            // 左侧分钟刻度：:15 / :45 各画在对应行的垂直中心
            if (gutter > 0f) {
                for ((slot, label) in Y_TICKS) {
                    canvas.drawText(label, 0f, cy[ROWS - 1 - slot] + tickPaint.textSize * 0.36f, tickPaint)
                }
            }

            if (axisH > 0f) {
                val baseline = h - 2f * density
                for (hour in intArrayOf(0, 6, 12, 18)) {
                    val label = hour.toString()
                    canvas.drawText(label, cx[hour] - tickPaint.measureText(label) / 2f, baseline, tickPaint)
                }
            }
            return bmp
        }

        /** 布局 widget_fragments.xml 的内边距与两行文字高度（dp），用于推算点阵可用高度 */
        private const val WIDGET_PADDING_H_DP = 20
        private const val WIDGET_PADDING_V_DP = 16
        private const val HEADER_DP = 18
        private const val STATS_DP = 17

        /** 2 格宽时统计行折成两行（起床 / 入睡 各一行），高度大约翻倍 */
        private const val STATS_DP_STACKED = 31

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
