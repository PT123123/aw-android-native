package net.activitywatch.android.focus

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 专注模块数据层 —— 契约 §5.8。
 *
 * 独立于 Todo 数据域：本地 `focus_local.json` 原子写，键名 snake_case。
 * 与 Todo 的唯一耦合点：计时页从 TodoSource::tasks() 读任务列表作为可选项。
 */
data class FocusSession(
    var id: Long = 0,
    var title: String = "",
    var taskId: Long = 0,          // 0 = 未关联任务
    var start: String = "",        // ISO: yyyy-MM-dd'T'HH:mm:ss
    var minutes: Int = 0,
)

/** 倒数日 / 纪念日（yearly = 每年重复） */
data class CountdownItem(
    var id: Long = 0,
    var title: String = "",
    var date: String = "",         // yyyy-MM-dd
    var yearly: Boolean = false,
)

private class FocusFile {
    var sessions: MutableList<FocusSession> = mutableListOf()
    var countdowns: MutableList<CountdownItem> = mutableListOf()
    var next_id: Long = 1
}

/** 专注数据的唯一入口；页面只认它，全部渲染由 onChange 驱动 */
object FocusStore {
    private const val FILE_NAME = "focus_local.json"

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    private var file: File? = null
    private var state = FocusFile()
    private val listeners = LinkedHashSet<() -> Unit>()

    /** 幂等初始化；每个专注页面 onCreateView 时调用一次 */
    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, FILE_NAME)
        runCatching {
            val f = file!!
            if (f.exists()) gson.fromJson(f.readText(), FocusFile::class.java)?.let { state = it }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(state))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    // ── 专注会话 ────────────────────────────────────────

    fun sessions(): List<FocusSession> = state.sessions.sortedByDescending { it.start }

    fun addSession(title: String, taskId: Long, startIso: String, minutes: Int) {
        if (minutes <= 0) return
        state.sessions.add(FocusSession(state.next_id++, title, taskId, startIso, minutes))
        save()
    }

    fun deleteSession(id: Long) {
        state.sessions.removeAll { it.id == id }
        save()
    }

    // ── 倒数日 / 纪念日 ─────────────────────────────────

    fun countdowns(): List<CountdownItem> = state.countdowns.sortedBy { it.date }

    fun addCountdown(title: String, date: String, yearly: Boolean) {
        if (title.isBlank() || date.isBlank()) return
        state.countdowns.add(CountdownItem(state.next_id++, title, date, yearly))
        save()
    }

    fun deleteCountdown(id: Long) {
        state.countdowns.removeAll { it.id == id }
        save()
    }
}

/** 模块显示开关（§5.8，等价 appsettings.h 的 FocusModules，8 个 bool 默认全开） */
object FocusModules {
    private const val PREFS_NAME = "focus_modules"

    val KEYS = listOf(
        "timer", "records", "record_detail", "timeline",
        "heatmap", "best", "calendar", "countdown",
    )

    val TITLES = mapOf(
        "timer" to "计时",
        "records" to "专注记录",
        "record_detail" to "专注记录详情",
        "timeline" to "专注时间线",
        "heatmap" to "热力图",
        "best" to "最佳专注时间",
        "calendar" to "日历",
        "countdown" to "倒数纪念日",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context, key: String): Boolean = prefs(context).getBoolean(key, true)

    fun setEnabled(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
}

/** 日期工具（沿用 SimpleDateFormat，避开 java.time 的 minSdk 限制） */
object FocusDates {
    val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val DISPLAY = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun nowIso(): String = ISO.format(Date())

    fun dayOf(iso: String): String = if (iso.length >= 10) iso.substring(0, 10) else ""

    fun hourOf(iso: String): Int = if (iso.length >= 13) iso.substring(11, 13).toIntOrNull() ?: 0 else 0

    fun minuteOf(iso: String): Int = if (iso.length >= 16) iso.substring(14, 16).toIntOrNull() ?: 0 else 0

    fun parseDay(s: String): Date? = try {
        DAY.parse(s)
    } catch (e: ParseException) {
        null
    }

    fun parseIso(iso: String): Date? = try {
        ISO.parse(iso)
    } catch (e: ParseException) {
        null
    }

    /** 今天 0 点的 Calendar */
    fun today(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** 距离目标还有几天；yearly = 自动取下一个周年 */
    fun daysUntil(dateStr: String, yearly: Boolean): Int {
        val target = parseDay(dateStr) ?: return 0
        val targetCal = Calendar.getInstance().apply {
            time = target
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayCal = today()
        if (yearly) {
            while (targetCal.before(todayCal)) targetCal.add(Calendar.YEAR, 1)
        }
        return ((targetCal.timeInMillis - todayCal.timeInMillis) / 86_400_000L).toInt()
    }
}
