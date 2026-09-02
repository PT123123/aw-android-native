package net.activitywatch.android.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 专注分析页：一个 Fragment 承载三个模块（契约 §5.8），
 * 抽屉入口通过 arguments 传 mode，页内 chips 也可切换：
 *   - timeline：专注时间线（选定日的 24h 条形）
 *   - heatmap：热力图（近 26 周 GitHub 风格）
 *   - best：最佳专注时间（按小时聚合的柱状）
 */
class FocusAnalyticsFragment : Fragment() {

    private var body: LinearLayout? = null
    private var mode = MODE_TIMELINE
    private var timelineDay: String = FocusDates.DAY.format(Date())

    private val changed: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getString(ARG_MODE) ?: MODE_TIMELINE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        FocusStore.init(requireContext())
        val title = when (mode) {
            MODE_HEATMAP -> "热力图"
            MODE_BEST -> "最佳专注时间"
            else -> "专注时间线"
        }
        val (toolbar, content) = FocusUi.buildRoot(this, title)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_focus_modules) {
                FocusUi.showModulesDialog(this) { rebuild() }
                true
            } else false
        }
        body = content
        FocusStore.addListener(changed)
        rebuild()
        return content.parent as View
    }

    override fun onDestroyView() {
        FocusStore.removeListener(changed)
        body = null
        super.onDestroyView()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val content = body ?: return
        content.removeAllViews()

        // 模块 chips
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(MODE_TIMELINE to "时间线", MODE_HEATMAP to "热力图", MODE_BEST to "最佳时间")
        for ((m, t) in defs) {
            chips.addView(FocusUi.chip(ctx, t, mode == m) {
                mode = m
                rebuild()
            })
            chips.addView(View(ctx), LinearLayout.LayoutParams(FocusUi.dp(ctx, 8), 1))
        }
        content.addView(chips, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, FocusUi.dp(ctx, 12))
        })

        val key = when (mode) {
            MODE_HEATMAP -> "heatmap"
            MODE_BEST -> "best"
            else -> "timeline"
        }
        if (!FocusModules.enabled(ctx, key)) {
            content.addView(FocusUi.disabledHint(this, key))
            return
        }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inner, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, ViewGroup.LayoutParams(-1, -1))

        when (mode) {
            MODE_HEATMAP -> buildHeatmap(ctx, inner)
            MODE_BEST -> buildBest(ctx, inner)
            else -> buildTimeline(ctx, inner)
        }
    }

    // ── 时间线 ──────────────────────────────────────────

    private fun buildTimeline(ctx: Context, into: LinearLayout) {
        // 日导航
        val nav = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val fmt = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.getDefault())
        val dayLabel = TextView(ctx).apply {
            text = fmt.formatOrNull(timelineDay)
            textSize = 15f
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
            gravity = android.view.Gravity.CENTER
        }
        nav.addView(navBtn(ctx, "‹") { shiftDay(-1, fmt, dayLabel) }, LinearLayout.LayoutParams(-2, -2))
        nav.addView(dayLabel, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(navBtn(ctx, "›") { shiftDay(1, fmt, dayLabel) }, LinearLayout.LayoutParams(-2, -2))
        into.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, FocusUi.dp(ctx, 12))
        })

        val sessions = FocusStore.sessions().filter { FocusDates.dayOf(it.start) == timelineDay }

        val view = DayTimelineView(ctx)
        view.sessions = sessions
        into.addView(view, ViewGroup.LayoutParams(-1, FocusUi.dp(ctx, 130)))

        if (sessions.isEmpty()) {
            into.addView(FocusUi.label(ctx, "这一天还没有专注记录", 13f, R.color.aw_text_disabled).apply {
                setPadding(0, FocusUi.dp(ctx, 12), 0, 0)
            })
            return
        }
        into.addView(FocusUi.label(ctx, "共 ${sessions.size} 次 · ${sessions.sumOf { it.minutes }} 分钟", 13f, R.color.aw_text_secondary).apply {
            setPadding(0, FocusUi.dp(ctx, 10), 0, FocusUi.dp(ctx, 4))
        })
        for (s in sessions.sortedBy { it.start }) {
            into.addView(FocusUi.card(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, FocusUi.dp(ctx, 8))
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(FocusUi.label(ctx, s.title, 14f, R.color.aw_text_primary, bold = true))
                    addView(FocusUi.label(ctx, timeRange(s.start, s.minutes), 12f, R.color.aw_text_secondary).apply {
                        setPadding(0, FocusUi.dp(ctx, 2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(FocusUi.label(ctx, "${s.minutes} 分钟", 13f, R.color.aw_accent, bold = true))
            })
        }
    }

    private fun shiftDay(delta: Int, fmt: SimpleDateFormat, label: TextView) {
        FocusDates.parseDay(timelineDay)?.let {
            val cal = Calendar.getInstance().apply { time = it; add(Calendar.DAY_OF_YEAR, delta) }
            timelineDay = FocusDates.DAY.format(cal.time)
            label.text = fmt.formatOrNull(timelineDay)
            rebuild()
        }
    }

    private fun timeRange(startIso: String, minutes: Int): String {
        val d = FocusDates.parseIso(startIso) ?: return startIso
        val start = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        val endCal = Calendar.getInstance().apply { time = d; add(Calendar.MINUTE, minutes) }
        val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(endCal.time)
        return "$start – $end"
    }

    // ── 热力图 ──────────────────────────────────────────

    private fun buildHeatmap(ctx: Context, into: LinearLayout) {
        into.addView(FocusUi.label(ctx, "近 26 周专注强度", 13f, R.color.aw_text_secondary).apply {
            setPadding(0, 0, 0, FocusUi.dp(ctx, 8))
        })
        val byDay = HashMap<String, Int>()
        for (s in FocusStore.sessions()) {
            val day = FocusDates.dayOf(s.start)
            if (day.isNotEmpty()) byDay[day] = (byDay[day] ?: 0) + s.minutes
        }
        val view = HeatmapView(ctx)
        view.minutesByDay = byDay
        into.addView(view, ViewGroup.LayoutParams(-1, FocusUi.dp(ctx, 170)))
        into.addView(FocusUi.label(ctx, "颜色越深 = 当日专注越久；点按格子查看详情", 12f, R.color.aw_text_disabled).apply {
            setPadding(0, FocusUi.dp(ctx, 8), 0, 0)
        })
    }

    // ── 最佳专注时间 ────────────────────────────────────

    private fun buildBest(ctx: Context, into: LinearLayout) {
        val byHour = IntArray(24)
        var count = 0
        for (s in FocusStore.sessions()) {
            val h = FocusDates.hourOf(s.start)
            byHour[h] += s.minutes
            count++
        }
        if (count == 0) {
            into.addView(FocusUi.label(ctx, "还没有专注数据", 14f, R.color.aw_text_disabled))
            return
        }
        val best = byHour.indices.maxByOrNull { byHour[it] } ?: 0
        into.addView(FocusUi.card(ctx).apply {
            addView(FocusUi.label(ctx, "最佳时段", 12f, R.color.aw_text_secondary))
            addView(FocusUi.label(ctx, "$best:00 – ${best + 1}:00", 18f, R.color.aw_accent, bold = true).apply {
                setPadding(0, FocusUi.dp(ctx, 2), 0, 0)
            })
            addView(FocusUi.label(
                ctx,
                "该时段累计 ${byHour[best]} 分钟，共 $count 次专注",
                12f, R.color.aw_text_secondary
            ).apply { setPadding(0, FocusUi.dp(ctx, 4), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, FocusUi.dp(ctx, 12)) })

        val view = BestHoursView(ctx)
        view.minutesByHour = byHour
        into.addView(view, ViewGroup.LayoutParams(-1, FocusUi.dp(ctx, 180)))
        into.addView(FocusUi.label(ctx, "按小时聚合的全部专注分钟数，深色为 Top 3", 12f, R.color.aw_text_disabled).apply {
            setPadding(0, FocusUi.dp(ctx, 8), 0, 0)
        })
    }

    private fun navBtn(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 20f
            setTextColor(FocusUi.color(ctx, R.color.aw_text_primary))
            setPadding(FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 4), FocusUi.dp(ctx, 12), FocusUi.dp(ctx, 4))
            setOnClickListener { onClick() }
        }

    private fun SimpleDateFormat.formatOrNull(day: String): String =
        FocusDates.parseDay(day)?.let { format(it) } ?: day

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_TIMELINE = "timeline"
        const val MODE_HEATMAP = "heatmap"
        const val MODE_BEST = "best"
    }
}

// ═══════════════════════════ 自定义视图 ═══════════════════════════

/** 单日 24 小时横向时间线 */
private class DayTimelineView(context: Context) : View(context) {

    var sessions: List<FocusSession> = emptyList()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_bar_track)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_accent)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_text_secondary)
        textSize = FocusUi.dp(context, 10).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val pad = FocusUi.dp(context, 16).toFloat()
        val trackTop = FocusUi.dp(context, 28).toFloat()
        val trackH = FocusUi.dp(context, 36).toFloat()
        val usable = w - pad * 2

        canvas.drawRoundRect(RectF(pad, trackTop, w - pad, trackTop + trackH), 10f, 10f, track)

        for (s in sessions) {
            val startMin = FocusDates.hourOf(s.start) * 60f + FocusDates.minuteOf(s.start)
            val left = pad + startMin / 1440f * usable
            val barW = max(s.minutes / 1440f * usable, FocusUi.dp(context, 6).toFloat())
            canvas.drawRoundRect(
                RectF(left, trackTop, (left + barW).coerceAtMost(w - pad), trackTop + trackH),
                10f, 10f, bar
            )
        }
        canvas.drawText("0:00", pad, trackTop + trackH + FocusUi.dp(context, 20).toFloat(), text)
        val mid = "12:00"
        val midW = text.measureText(mid)
        canvas.drawText(mid, pad + usable / 2 - midW / 2, trackTop + trackH + FocusUi.dp(context, 20).toFloat(), text)
        val end = "24:00"
        val endW = text.measureText(end)
        canvas.drawText(end, w - pad - endW, trackTop + trackH + FocusUi.dp(context, 20).toFloat(), text)
    }
}

/** 近 26 周 GitHub 风格热力图 */
private class HeatmapView(context: Context) : View(context) {

    var minutesByDay: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            computeGrid()
            invalidate()
        }

    private var cell = 0f
    private var gap = FocusUi.dp(context, 3).toFloat()
    private var pad = FocusUi.dp(context, 12).toFloat()
    private val weeks = 26

    private var grid: List<List<Pair<String, Int>>> = emptyList() // [week][dayOffset] -> (dateStr, minutes)

    init {
        computeGrid()
    }

    private fun computeGrid() {
        // 以周一为一周起点，向回推 26 周
        val end = FocusDates.today()
        val dow = end.get(Calendar.DAY_OF_WEEK) // SUN=1..SAT=7
        val backToMonday = (dow + 5) % 7        // MON -> 0
        end.add(Calendar.DAY_OF_YEAR, -backToMonday)
        val list = mutableListOf<List<Pair<String, Int>>>()
        val cal = end.clone() as Calendar
        cal.add(Calendar.WEEK_OF_YEAR, -(weeks - 1))
        for (w in 0 until weeks) {
            val col = mutableListOf<Pair<String, Int>>()
            for (d in 0 until 7) {
                val key = FocusDates.DAY.format(cal.time)
                col.add(key to (minutesByDay[key] ?: 0))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            list.add(col)
        }
        grid = list
    }

    private fun bucketColor(minutes: Int): Int {
        val base = FocusUi.color(context, R.color.aw_accent)
        val alpha = when {
            minutes <= 0 -> 0
            minutes < 30 -> 70
            minutes < 60 -> 130
            minutes < 120 -> 190
            else -> 255
        }
        return if (alpha == 0) FocusUi.color(context, R.color.aw_surface_2) else (base and 0x00FFFFFF) or (alpha shl 24)
    }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cell = ((w - pad * 2 - gap * (weeks - 1)) / weeks).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 0 until weeks) {
            for (j in 0 until 7) {
                val (_, minutes) = grid[i][j]
                cellPaint.color = bucketColor(minutes)
                val x = pad + i * (cell + gap)
                val y = pad + j * (cell + gap)
                canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), 3f, 3f, cellPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && cell > 0) {
            val i = ((event.x - pad) / (cell + gap)).roundToInt()
            val j = ((event.y - pad) / (cell + gap)).roundToInt()
            if (i in 0 until weeks && j in 0 until 7) {
                val (day, minutes) = grid[i][j]
                Toast.makeText(
                    context,
                    if (minutes > 0) "$day：专注 $minutes 分钟" else "$day：无专注",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}

/** 按小时聚合的柱状图（Top 3 高亮） */
private class BestHoursView(context: Context) : View(context) {

    var minutesByHour: IntArray = IntArray(24)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_bar_track)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_accent)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_border)
        strokeWidth = FocusUi.dp(context, 1).toFloat()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FocusUi.color(context, R.color.aw_text_secondary)
        textSize = FocusUi.dp(context, 10).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = FocusUi.dp(context, 12).toFloat()
        val labelH = FocusUi.dp(context, 18).toFloat()
        val chartH = h - pad - labelH
        val maxVal = (minutesByHour.maxOrNull() ?: 0).coerceAtLeast(1)
        val top3 = minutesByHour.indices.sortedByDescending { minutesByHour[it] }.take(3).toSet()
        val colW = (w - pad * 2) / 24f

        canvas.drawLine(pad, chartH, w - pad, chartH, axis)
        for (hour in 0 until 24) {
            val v = minutesByHour[hour]
            val barH = if (v <= 0) FocusUi.dp(context, 2).toFloat()
            else max(v.toFloat() / maxVal * (chartH - FocusUi.dp(context, 8)), FocusUi.dp(context, 3).toFloat())
            val x = pad + hour * colW + colW * 0.15f
            val barW = colW * 0.7f
            val paint = if (hour in top3 && v > 0) bar else track
            canvas.drawRoundRect(
                RectF(x, chartH - barH, x + barW, chartH),
                4f, 4f, paint
            )
        }
        for (hour in listOf(0, 6, 12, 18)) {
            val label = "$hour"
            val tw = text.measureText(label)
            val cx = pad + hour * colW + colW / 2 - tw / 2
            canvas.drawText(label, cx.coerceAtLeast(pad), h - FocusUi.dp(context, 4).toFloat(), text)
        }
        // 24 刻度右对齐
        val endLabel = "24"
        canvas.drawText(
            endLabel,
            w - pad - text.measureText(endLabel),
            h - FocusUi.dp(context, 4).toFloat(),
            text
        )
    }
}
