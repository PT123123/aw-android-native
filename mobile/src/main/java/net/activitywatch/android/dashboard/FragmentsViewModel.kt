package net.activitywatch.android.dashboard

import android.app.Application
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
 * 「碎片」页数据源：一次只看一天，翻页只改日期，扫描交给 [DayFragments]
 * （与桌面「今日碎片」小部件共用同一份实现，两边逐格一致）。
 */
class FragmentsViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val _state = MutableStateFlow(FragmentsState())
    val state = _state.asStateFlow()

    /** 排行最多列多少行 */
    private val launchRows = 15

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

            val todayStart = startOfDayMs(System.currentTimeMillis())
            val permitted = UsageStatsWatcher.isUsageAllowed(appContext)
            // 未授权 / 扫描失败都会拿到 null：数据清空，但日期导航与权限提示照常
            val scanned = if (permitted) DayFragments.scan(appContext, day) else null

            val pm = appContext.packageManager
            val maxLaunch = scanned?.launches?.values?.maxOrNull() ?: 0
            val launchList = scanned?.launches?.entries
                ?.sortedByDescending { it.value }
                ?.take(launchRows)
                ?.map { (pkg, count) ->
                    LaunchCount(
                        ScreenTimeStats.resolveLabel(pm, pkg),
                        count,
                        if (maxLaunch > 0) count.toFloat() / maxLaunch else 0f,
                    )
                }
                .orEmpty()

            _state.update {
                it.copy(
                    loading = false,
                    permitted = permitted,
                    dayStartMs = day,
                    dayLabel = dayLabelOf(day),
                    canPrev = day > earliestDayMs(),
                    canNext = day < todayStart,
                    slots = scanned?.slots ?: emptyList(),
                    launches = launchList,
                    wake = scanned?.wakeMs?.let { ms -> fmtHm(minuteOfDay(ms)) },
                    sleep = scanned?.sleepMs?.let { ms -> fmtHm(minuteOfDay(ms)) },
                )
            }
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
}
