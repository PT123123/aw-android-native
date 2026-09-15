package net.activitywatch.android.dashboard

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.activitywatch.android.widget.ScreenTimeStats
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "FragmentsViewModel"

/** 每个圆点的时间粒度：5 分钟（每天 24 时 × 每时 12 槽 = 288 点） */
private const val SLOT_MIN = 5

data class LaunchCount(val label: String, val count: Int, val ratio: Float)

data class FragmentsState(
    val loading: Boolean = false,
    /** 是否已授予「使用情况访问」权限 */
    val permitted: Boolean = true,
    /** 当前查看的自然日 0 点 */
    val dayStartMs: Long = 0L,
    /** 日期标题，如「9月16日 · 今天」 */
    val dayLabel: String = "",
    val canPrev: Boolean = true,
    val canNext: Boolean = false,
    /** 单日碎片格：288 个 5 分钟槽的使用秒数，顺序 = 槽(0..11) × 时(0..23)，下标 slot*24+hour */
    val slots: List<Double> = emptyList(),
    val launches: List<LaunchCount> = emptyList(),
    /** 当天推断出的作息（HH:mm），null = 无从推断 */
    val wake: String? = null,
    val sleep: String? = null,
)

/**
 * 「碎片」页数据源：一次只看一天，直读系统 UsageStatsManager 的事件明细，
 * 不依赖 Rust 服务器。页面通过 prevDay/nextDay 翻看历史。
 */
class FragmentsViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val _state = MutableStateFlow(FragmentsState())
    val state = _state.asStateFlow()

    private var dayStartMs = startOfDayMs(System.currentTimeMillis())

    init {
        load()
    }

    fun reload() = load()

    /** 翻到前一天（最早回看 30 天） */
    fun prevDay() {
        if (dayStartMs > earliestDayMs()) {
            dayStartMs = shiftDay(dayStartMs, -1)
            load()
        }
    }

    /** 翻到后一天（不能超过今天） */
    fun nextDay() {
        val today = startOfDayMs(System.currentTimeMillis())
        if (dayStartMs < today) {
            dayStartMs = minOf(shiftDay(dayStartMs, 1), today)
            load()
        }
    }

    private fun load() {
        val day = dayStartMs
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true) }
            if (!UsageStatsWatcher.isUsageAllowed(appContext)) {
                _state.update {
                    it.copy(loading = false, permitted = false, dayStartMs = day, dayLabel = dayLabelOf(day),
                        canPrev = day > earliestDayMs(), canNext = day < startOfDayMs(System.currentTimeMillis()),
                        slots = emptyList(), launches = emptyList(), wake = null, sleep = null)
                }
                return@launch
            }

            try {
                val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm == null) {
                    _state.update { it.copy(loading = false, dayStartMs = day, dayLabel = dayLabelOf(day),
                        slots = emptyList(), launches = emptyList(), wake = null, sleep = null) }
                    return@launch
                }

                val dayEnd = day + DAY_MS
                // 窗口延到次日中午：睡前的会话常跨过午夜（如 23:50–00:20），
                // PAUSED 落在次日，若把窗口掐在午夜就会把入睡错误地闭合成 23:59
                val endMs = minOf(dayEnd + SLEEP_WINDOW_MS, System.currentTimeMillis())

                // 单日的 288 个 5 分钟槽（时 × 槽），跨小时的会话按分钟切分
                val slotSec = DoubleArray(24 * 12)
                val launches = HashMap<String, Int>()
                var wakeCand: Long? = null
                var lastEnd: Long? = null
                var openStart = -1L
                var strayTailDone = false

                fun closeSession(end: Long) {
                    if (end <= openStart) return
                    // 热力只统计当天 0 点内的部分，跨午夜部分属于第二天
                    addUsage(openStart, minOf(end, dayEnd), slotSec)
                    // 入睡归属：会话开始于 [当天0点, 次日4点) 的，其真实结束时间记为当天入睡——
                    // 熬夜到次日早上 6 点的场景，入睡就显示 06:00 而不是被截断的 23:59
                    if (openStart >= day && openStart < dayEnd + WAKE_CUTOFF_MS) {
                        lastEnd = maxOf(lastEnd ?: 0L, end)
                    }
                }

                val ev = UsageEvents.Event()
                val events = usm.queryEvents(day, endMs)
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    when (ev.eventType) {
                        // 每次 App 到前台计一次启动（只算当天内的）
                        EVT_RESUMED -> {
                            val pkg = ev.packageName ?: continue
                            if (ev.timeStamp < dayEnd) launches[pkg] = (launches[pkg] ?: 0) + 1
                            // 个别 ROM 缺 PAUSED 时兜底闭合上一段
                            if (openStart >= 0) closeSession(ev.timeStamp)
                            openStart = ev.timeStamp
                            // 起床候选：当天凌晨 4 点后（含）到当天 24 点前的首次拿起
                            if (ev.timeStamp >= day + WAKE_CUTOFF_MS && ev.timeStamp < dayEnd &&
                                (wakeCand == null || ev.timeStamp < wakeCand)
                            ) wakeCand = ev.timeStamp
                        }
                        EVT_PAUSED, EVT_STOPPED -> {
                            if (openStart >= 0) {
                                closeSession(ev.timeStamp)
                                openStart = -1L
                            } else if (ev.eventType == EVT_PAUSED && !strayTailDone && ev.timeStamp > day) {
                                // 会话开始于窗口之前（如前一天的睡前使用跨到今天凌晨）：
                                // 只补热力，不计入睡/启动。PAUSED 与 STOPPED 可能成对出现，只处理一次
                                strayTailDone = true
                                addUsage(day, minOf(ev.timeStamp, dayEnd), slotSec)
                            }
                        }
                    }
                }
                if (openStart >= 0) closeSession(endMs)

                // 转成「行=5 分钟槽、列=小时」的点阵顺序
                val flat = DoubleArray(24 * 12)
                for (h in 0 until 24) for (s in 0 until 12) flat[s * 24 + h] = slotSec[h * 12 + s]

                val pm = appContext.packageManager
                val maxLaunch = launches.values.maxOrNull() ?: 0
                val launchList = launches.entries
                    .sortedByDescending { it.value }
                    .take(15)
                    .map { (pkg, count) ->
                        LaunchCount(ScreenTimeStats.resolveLabel(pm, pkg), count,
                            if (maxLaunch > 0) count.toFloat() / maxLaunch else 0f)
                    }

                _state.update {
                    it.copy(
                        loading = false,
                        permitted = true,
                        dayStartMs = day,
                        dayLabel = dayLabelOf(day),
                        canPrev = day > earliestDayMs(),
                        canNext = day < startOfDayMs(System.currentTimeMillis()),
                        slots = flat.toList(),
                        launches = launchList,
                        wake = wakeCand?.let { ms -> fmtHm(minuteOfDay(ms)) },
                        sleep = lastEnd?.let { ms -> fmtHm(minuteOfDay(ms)) },
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "load($day) failed", e)
                _state.update { it.copy(loading = false, dayStartMs = day, dayLabel = dayLabelOf(day),
                    slots = emptyList(), launches = emptyList(), wake = null, sleep = null) }
            }
        }
    }

    /** 把一段使用时长拆进 5 分钟槽：下标 = hour*12 + minute/5 */
    private fun addUsage(s: Long, e: Long, out: DoubleArray) {
        val cal = Calendar.getInstance()
        var t = s
        while (t < e) {
            cal.timeInMillis = t
            val minute = cal.get(Calendar.MINUTE)
            cal.set(Calendar.MINUTE, minute - minute % SLOT_MIN)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val slotStart = cal.timeInMillis
            val idx = cal.get(Calendar.HOUR_OF_DAY) * 12 + cal.get(Calendar.MINUTE) / SLOT_MIN
            val segEnd = minOf(e, slotStart + SLOT_MIN * 60_000L)
            out[idx] += (segEnd - t) / 1000.0
            t = segEnd
        }
    }

    private fun earliestDayMs(): Long = shiftDay(startOfDayMs(System.currentTimeMillis()), -29)

    private fun shiftDay(ms: Long, delta: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_MONTH, delta)
        }
        return c.timeInMillis
    }

    private fun dayLabelOf(dayStart: Long): String {
        val today = startOfDayMs(System.currentTimeMillis())
        val base = SimpleDateFormat("M月d日", Locale.getDefault()).format(dayStart)
        return when (dayStart) {
            today -> "$base · 今天"
            shiftDay(today, -1) -> "$base · 昨天"
            else -> base
        }
    }

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 分钟数 → HH:mm 文本 */
    private fun fmtHm(minute: Int): String =
        String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24 * 3600_000L

        /** 起床推断的凌晨截断：4 点前的深夜使用不计入「起床」 */
        private const val WAKE_CUTOFF_MS = 4 * 3600_000L

        /** 入睡归属的窗口延伸：事件查询延到次日中午，覆盖跨午夜会话的结束事件 */
        private const val SLEEP_WINDOW_MS = 12 * 3600_000L

        // UsageEvents 事件类型：API 29 改名（MOVE_TO_FOREGROUND→ACTIVITY_RESUMED 等）但数值不变；
        // minSdk 24 直接引用新常量会触发 lint NewApi，故用字面量
        private const val EVT_RESUMED = 1
        private const val EVT_PAUSED = 2
        private const val EVT_STOPPED = 23
    }
}
