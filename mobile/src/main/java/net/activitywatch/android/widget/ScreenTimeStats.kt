package net.activitywatch.android.widget

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.activitywatch.android.watcher.UsageStatsWatcher

/**
 * 桌面小部件的数据源：今日屏幕使用时长。
 *
 * 直接读系统 UsageStatsManager（与应用内 Rust 服务器是否运行无关），
 * 统计口径与系统设置的「屏幕使用时间」一致：各应用当日前台时长之和。
 */
object ScreenTimeStats {
    private const val TAG = "ScreenTimeStats"

    /** 单个应用的今日前台使用时长 */
    data class AppUsage(val packageName: String, val label: String, val ms: Long)

    /**
     * @param permitted 是否已授予「使用情况访问」权限（未授权时 totalMs/topApps 无意义）
     */
    data class Result(val permitted: Boolean, val totalMs: Long, val topApps: List<AppUsage>)

    /** 查询今日（本地时区 0 点至今）各应用前台时长，返回总时长和耗时前三的应用 */
    fun today(context: Context): Result {
        if (!UsageStatsWatcher.isUsageAllowed(context)) {
            return Result(permitted = false, totalMs = 0L, topApps = emptyList())
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return Result(permitted = true, totalMs = 0L, topApps = emptyList())

        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val perPkg = HashMap<String, Long>()
        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            for (s in stats ?: emptyList()) {
                // 过滤掉完全落在今天之前的桶（部分 ROM 的日桶可能跨天）
                if (s.lastTimeStamp < start) continue
                val t = s.totalTimeInForeground
                if (t <= 0) continue
                perPkg[s.packageName] = (perPkg[s.packageName] ?: 0L) + t
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询 UsageStats 失败", e)
        }

        var total = 0L
        for (v in perPkg.values) total += v

        val pm = context.packageManager
        val top = perPkg.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { AppUsage(it.key, resolveLabel(pm, it.key), it.value) }

        return Result(permitted = true, totalMs = total, topApps = top)
    }

    private fun resolveLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }

    /** 中文口径的时长格式：3小时25分 / 45分钟 / 不足1分钟 */
    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分"
            h > 0 -> "${h}小时"
            m > 0 -> "${m}分钟"
            else -> "不足1分钟"
        }
    }
}
