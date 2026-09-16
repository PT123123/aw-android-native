package net.activitywatch.android.widget

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.util.Calendar

/**
 * 桌面小部件与仪表盘共用的「系统口径」屏幕时间数据源。
 *
 * 两个口径不要混用：
 * - **总量**（[queryDailyUnion] / [today] 的 totalMs）= 事件并集，同一时刻只算一次，
 *   与系统设置的「屏幕使用时间」一致；
 * - **逐包明细**（[queryDailyUsage]）= UsageStats 的 totalTimeInForeground，
 *   与系统「应用使用时长」列表同源（桌面等系统组件本来就占大头，别拿它去凑总和）。
 *
 * 两者都不依赖应用内 Rust 服务器是否运行、也不受本应用采集进程是否存活影响。
 */
object ScreenTimeStats {
    private const val TAG = "ScreenTimeStats"

    private const val DAY_MS = 24 * 3600_000L

    /** 单个应用的今日前台使用时长 */
    data class AppUsage(val packageName: String, val label: String, val ms: Long)

    /**
     * @param permitted 是否已授予「使用情况访问」权限（未授权时 totalMs/topApps 无意义）
     * @param totalMs   今日屏幕使用时长（并集口径，与系统「屏幕使用时间」一致）
     */
    data class Result(val permitted: Boolean, val totalMs: Long, val topApps: List<AppUsage>)

    /** 单个本地自然日的各应用前台时长（dayStartMs 为该日 0 点，本地时区） */
    data class DailyUsage(val dayStartMs: Long, val perPkgMs: Map<String, Long>)

    // UsageEvents 事件类型：API 29 改名（MOVE_TO_FOREGROUND→ACTIVITY_RESUMED 等）但数值不变；
    // minSdk 24 直接引用新常量会触发 lint NewApi，故用字面量（与 FragmentsViewModel 一致）
    private const val EVT_RESUMED = 1
    private const val EVT_PAUSED = 2
    private const val EVT_SCREEN_NON_INTERACTIVE = 16
    private const val EVT_STOPPED = 23

    /**
     * 事件并集最多回溯多少天。
     *
     * 系统的 UsageEvents 只保留最近若干个「日统计文件」（本机实测 10 个），
     * 再往前 `queryEvents` 查不到东西；逐日趋势里超出这段范围的日子由调用方回退逐包求和。
     */
    const val EVENT_LOOKBACK_DAYS = 10

    /**
     * 并集查询向前多取的一段。窗口起点时很可能已有会话在跑（前夜用到今天凌晨），
     * 只在窗口内查事件看不到它的 RESUMED，这段使用就会被整段漏掉（凌晨显示「不足1分钟」）。
     */
    private const val PRE_WINDOW_MS = 12 * 3600_000L

    // ===================== 总量：事件并集 =====================

    /**
     * 查询 [startDayMs, endMs) 内**逐本地自然日**的屏幕使用时长（并集口径，同一时刻只算一次）。
     *
     * 为什么不累加逐包 totalTimeInForeground：那是「每个应用各自的前台时长」，
     * 多个包同时处于前台（MIUI 上桌面 / 小窗 / 系统组件都会被记成前台）时会重复计时。
     * 本机实测同一天逐包求和 8h47m、事件并集 7h24m、系统 screen-interactive 7h15m
     * —— 逐包求和虚高约 20%，控件上就是「比系统屏幕使用时间偏大」。
     *
     * 会话切分规则与「碎片」页热力图完全一致：
     * - `ACTIVITY_RESUMED` 开一段（上一段立即闭合，故相邻的秒级包间切换不算空隙）；
     * - `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` 只闭合**同包**的段（别的包暂停与我们无关，
     *   个别 ROM 会在没有 RESUMED 的情况下补发 PAUSED）；
     * - `SCREEN_NON_INTERACTIVE` 息屏立即闭合，避免 ROM 漏发 PAUSED 时把息屏时间算进去；
     * - 事件往前多查 [PRE_WINDOW_MS]，好认出窗口起点时已经在跑、并延续到窗口内的会话
     *   （跨午夜的使用），它只统计窗口内的那部分。
     *
     * 未授权 / 异常返回空表。跨自然日的长会话按天切开，各自计入所属那天。
     */
    fun queryDailyUnion(context: Context, startDayMs: Long, endMs: Long): Map<Long, Long> {
        if (endMs <= startDayMs) return emptyMap()
        if (!UsageStatsWatcher.isUsageAllowed(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()

        val out = HashMap<Long, Long>()
        try {
            var openStart = -1L
            var openPkg: String? = null
            // 窗口里的第一个事件：用来认出「前一个会话在窗口之前就开始了」这种无头会话
            var firstEvent = true

            fun close(end: Long) {
                val s = openStart
                openStart = -1L
                openPkg = null
                if (s < 0) return
                val e = if (end > endMs) endMs else end
                // 会话可能开始于窗口之前（前夜用到今天凌晨），只统计窗口内的部分
                val from = if (s < startDayMs) startDayMs else s
                if (e > from) addSpan(from, e, out)
            }

            val ev = UsageEvents.Event()
            // 向前多取一段：窗口起点时可能有会话正在跑（如 23:00 一直用到今天 01:00），
            // 只看窗口内的事件会漏掉它的 RESUMED，把「此刻还在用」算成 0
            val events = usm.queryEvents(startDayMs - PRE_WINDOW_MS, endMs)
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val t = ev.timeStamp
                val isFirst = firstEvent
                firstEvent = false
                when (ev.eventType) {
                    EVT_RESUMED -> {
                        close(t)
                        openStart = t
                        openPkg = ev.packageName
                    }
                    EVT_PAUSED, EVT_STOPPED -> {
                        if (openStart >= 0) {
                            if (openPkg == ev.packageName) close(t)
                        } else if (isFirst && ev.eventType == EVT_PAUSED && t > startDayMs) {
                            // 窗口第一个事件就是 PAUSED：会话开始于更早之前，从窗口起点补这一段
                            // （正常情况下的孤儿 PAUSED 都不是第一个事件，不会被误当成会话）
                            openStart = startDayMs
                            close(t)
                        }
                    }
                    EVT_SCREEN_NON_INTERACTIVE -> close(t)
                }
            }
            if (openStart >= 0) close(endMs)
        } catch (e: Exception) {
            Log.w(TAG, "查询 UsageEvents 失败", e)
        }
        return out
    }

    /** 把一段连续使用切成按本地自然日的片段累加（跨天只切一刀，中国时区无夏令时也照切） */
    private fun addSpan(start: Long, end: Long, out: HashMap<Long, Long>) {
        var t = start
        while (t < end) {
            val dayStart = startOfDayMs(t)
            val dayEnd = nextDayStartMs(dayStart)
            val segEnd = if (end < dayEnd) end else dayEnd
            if (segEnd <= t) break
            out[dayStart] = (out[dayStart] ?: 0L) + (segEnd - t)
            t = segEnd
        }
    }

    // ===================== 逐包明细：UsageStats =====================

    /**
     * 查询 [startDayMs, endMs] 区间内按本地自然日归属的各应用前台时长（与系统
     * 「应用使用时长」列表同源；**不要把这些值加起来当屏幕使用时长**）。
     *
     * 系统的 INTERVAL_DAILY 日桶边界由系统决定，本机实测是 **凌晨 01:38**（不是 0 点，
     * 由系统启动时刻推算），所以这里把每个日桶整体归到其区间起点（firstTimeStamp）
     * 所在的本地自然日——跨桶的边界误差只有第一条/最后一条记录那几分钟，可接受。
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

    /** 查询今日（本地时区 0 点至今）屏幕使用时长与耗时前三的应用 */
    fun today(context: Context): Result {
        if (!UsageStatsWatcher.isUsageAllowed(context)) {
            return Result(permitted = false, totalMs = 0L, topApps = emptyList())
        }
        val now = System.currentTimeMillis()
        val todayStart = startOfDayMs(now)

        // 总量：事件并集（与系统「屏幕使用时间」同口径）
        val total = queryDailyUnion(context, todayStart, now)[todayStart] ?: 0L

        // 明细：逐包前台时长（与系统「应用使用时长」列表同口径）
        val usage = queryDailyUsage(context, todayStart, now)
            .firstOrNull { it.dayStartMs == todayStart }
        val perPkg = usage?.perPkgMs ?: emptyMap()

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

    /** 某时刻所在本地自然日的 0 点 */
    fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 本地自然日 0 点的次日 0 点（用 Calendar 推，避免夏令时地区少算/多算一小时） */
    private fun nextDayStartMs(dayStartMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayStartMs
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** 事件并集的可查范围起点：最近 [EVENT_LOOKBACK_DAYS] 天 */
    fun unionEarliestMs(nowMs: Long = System.currentTimeMillis()): Long =
        startOfDayMs(nowMs) - EVENT_LOOKBACK_DAYS * DAY_MS
}
