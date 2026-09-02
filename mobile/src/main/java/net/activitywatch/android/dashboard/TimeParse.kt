package net.activitywatch.android.dashboard

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间戳与日/小时边界的换算工具。
 *
 * 全部走 java.util.Calendar（本地时区），不用 java.time / threetenbp：
 * 项目 minSdk 24 且未启用 desugaring，threetenbp 又必须先 AndroidThreeTen.init()
 * 注册时区数据，否则 ZoneId.now() 直接抛 "No timezone data files registered"。
 *
 * aw-server 返回的 timestamp 形如 2026-09-02T10:23:45.123456+08:00，
 * 小数部分位数不固定（3~9 位），SimpleDateFormat 的 SSS 只认毫秒且会把多出的
 * 数字当成分隔符解析失败，所以这里用正则手工解析。
 */
private val ISO_RE = Regex(
    """(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:?\d{2})?"""
)

/** 解析 ISO-8601 时间戳为 epoch millis；无法识别时返回 null。 */
fun parseIsoToMillis(raw: String): Long? {
    val m = ISO_RE.find(raw) ?: return null
    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].toInt() - 1
    val day = m.groupValues[3].toInt()
    val hour = m.groupValues[4].toInt()
    val minute = m.groupValues[5].toInt()
    val second = m.groupValues[6].toInt()

    // 小数部分取前 3 位当毫秒，不足补 0（123456 -> 123，12 -> 120）
    val frac = m.groupValues[7]
    val millis = if (frac.isEmpty()) 0 else frac.take(3).padEnd(3, '0').toInt()

    val zone = m.groupValues[8]
    val offsetMinutes = when {
        zone.isEmpty() -> 0
        zone.equals("Z", ignoreCase = true) -> 0
        else -> {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val hh = digits.substring(0, 2).toInt()
            val mm = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            sign * (hh * 60 + mm)
        }
    }

    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month, day, hour, minute, second)
    // 先按 UTC 取毫秒，再减去该时间戳自带的时区偏移，得到真实的 UTC 时刻
    return cal.timeInMillis + millis - offsetMinutes * 60_000L
}

/** 该时刻所在本地自然日的 00:00:00.000。 */
fun startOfDayMs(ms: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 该时刻所在本地小时的整点。 */
fun startOfHourMs(ms: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * 把一段 [startMs, startMs + durSec) 按时切分到各个自然日，
 * 保证跨天的长事件不会把时长全算在起始那一天。
 */
fun forEachDaySlot(startMs: Long, durSec: Double, out: (dayStartMs: Long, sec: Double) -> Unit) {
    var cursor = startMs
    var remainMs = durSec * 1000.0
    var guard = 0
    while (remainMs > 0 && guard++ < 4096) {
        val dayStart = startOfDayMs(cursor)
        val nextDayStart = startOfDayMs(dayStart + 24 * 3600_000L)
        val slotMs = minOf(remainMs, (nextDayStart - cursor).toDouble())
        if (slotMs <= 0) break
        out(dayStart, slotMs / 1000.0)
        remainMs -= slotMs
        cursor = (cursor + slotMs).toLong()
    }
}

/** 同上，按自然小时切分。 */
fun forEachHourSlot(startMs: Long, durSec: Double, out: (hourStartMs: Long, sec: Double) -> Unit) {
    var cursor = startMs
    var remainMs = durSec * 1000.0
    var guard = 0
    while (remainMs > 0 && guard++ < 4096) {
        val hourStart = startOfHourMs(cursor)
        val nextHourStart = startOfHourMs(hourStart + 3600_000L)
        val slotMs = minOf(remainMs, (nextHourStart - cursor).toDouble())
        if (slotMs <= 0) break
        out(hourStart, slotMs / 1000.0)
        remainMs -= slotMs
        cursor = (cursor + slotMs).toLong()
    }
}

/** HH:mm，24 小时制。 */
fun formatClock(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** M/d，日期轴与趋势卡片用。 */
fun formatDayLabel(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%d/%d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
}

/** yyyy-MM-dd，秒表记录与查询时间区间用。 */
fun formatFullDate(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
    )
}

/**
 * 时间范围对应的实际毫秒窗口，Query Explorer 拼 timeperiods 用。
 * ALL 从 1970 起算——aw-server 的 TimeInterval 解析得动，且不会漏掉老数据。
 */
fun rangeWindowMs(range: TimeRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val todayStart = startOfDayMs(now)
    val day = 24 * 3600_000L
    return when (range) {
        TimeRange.TODAY -> todayStart to now
        TimeRange.YESTERDAY -> startOfDayMs(todayStart - day) to todayStart
        TimeRange.LAST7 -> startOfDayMs(todayStart - 6 * day) to now
        TimeRange.LAST30 -> startOfDayMs(todayStart - 29 * day) to now
        TimeRange.ALL -> 0L to now
    }
}

/** 毫秒时刻转 ISO-8601（带本地时区偏移），写事件 / 查询区间用。 */
fun isoOf(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))

/** HH:mm:ss，秒表读数。 */
fun formatHms(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
