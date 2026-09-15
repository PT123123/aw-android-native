package net.activitywatch.android.widget

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.util.Calendar

/**
 * 桌面小部件与仪表盘共用的「系统口径」屏幕时间数据源。
 *
 * 直接读系统 UsageStatsManager 的 totalTimeInForeground（系统自己累计的前台时长，
 * 与系统设置的「屏幕使用时间」一致），不依赖应用内 Rust 服务器是否运行、
 * 也不受本应用采集进程是否存活影响。
 */
object ScreenTimeStats {
    private const val TAG = "ScreenTimeStats"

    /** 单个应用的今日前台使用时长 */
    data class AppUsage(val packageName: String, val label: String, val ms: Long)

    /**
     * @param permitted 是否已授予「使用情况访问」权限（未授权时 totalMs/topApps 无意义）
     */
    data class Result(val permitted: Boolean, val totalMs: Long, val topApps: List<AppUsage>)

    /** 单个本地自然日的各应用前台时长（dayStartMs 为该日 0 点，本地时区） */
    data class DailyUsage(val dayStartMs: Long, val perPkgMs: Map<String, Long>)

    /**
     * 查询 [startDayMs, endMs] 区间内按本地自然日归属的各应用前台时长。
     *
     * 系统的 INTERVAL_DAILY 日桶边界由系统决定（多数 ROM 按本地午夜，个别按 UTC），
     * 这里把每个日桶整体归到其区间起点（firstTimeStamp）所在的本地自然日。
     *
     * @param startDayMs 起始日的 0 点（本地时区）；传 0 表示从系统保留的最早数据开始
     */
    fun queryDailyUsage(context: Context, startDayMs: Long, endMs: Long): List<DailyUsage> {
        if (!UsageStatsWatcher.isUsageAllowed(context)) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val byDay = sortedMapOf<Long, HashMap<String, Long>>()
        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startDayMs, endMs)
            for (s in stats ?: emptyList()) {
                val t = s.totalTimeInForeground
                if (t <= 0) continue
                val day = startOfDayMs(s.firstTimeStamp)
                val perPkg = byDay.getOrPut(day) { HashMap() }
                perPkg[s.packageName] = (perPkg[s.packageName] ?: 0L) + t
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询 UsageStats 失败", e)
        }
        return byDay.map { (day, perPkg) -> DailyUsage(day, perPkg) }
    }

    /** 查询今日（本地时区 0 点至今）各应用前台时长，返回总时长和耗时前三的应用 */
    fun today(context: Context): Result {
        if (!UsageStatsWatcher.isUsageAllowed(context)) {
            return Result(permitted = false, totalMs = 0L, topApps = emptyList())
        }
        val todayStart = startOfDayMs(System.currentTimeMillis())
        val usage = queryDailyUsage(context, todayStart, System.currentTimeMillis())
            .firstOrNull { it.dayStartMs == todayStart }
        val perPkg = usage?.perPkgMs ?: emptyMap()

        var total = 0L
        for (v in perPkg.values) total += v

        val pm = context.packageManager
        val top = perPkg.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { AppUsage(it.key, resolveLabel(pm, it.key), it.value) }

        return Result(permitted = true, totalMs = total, topApps = top)
    }

    /**
     * 包名 → 应用名。与 Event.fromUsageEvent 的解析保持同源同兜底
     * （MATCH_UNINSTALLED_PACKAGES、失败回落完整包名），保证事件流与聚合
     * 榜单的标签一致、颜色可对齐。
     */
    fun resolveLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(
            pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
        ).toString()
    } catch (e: Exception) {
        pkg
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

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
