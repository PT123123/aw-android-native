package net.activitywatch.android.dashboard

/** 把秒数格式化为紧凑的时长字符串，如 1h23m / 45m / 0m。 */
fun formatDuration(sec: Double): String {
    if (sec <= 0) return "0m"
    val totalMin = (sec / 60.0).toLong()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
