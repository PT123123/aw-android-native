package net.activitywatch.android.dashboard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.util.Calendar

/**
 * 单日「碎片」扫描：把某天的事件明细摊成 288 个 5 分钟槽（24 时 × 每时 12 槽），
 * 并顺手推断起床 / 入睡、统计各 App 启动次数。
 *
 * 「活动 · 碎片」Tab 与桌面「今日碎片」小部件共用这一份实现 —— 两处画出来的
 * 热力图与总时长必须逐格一致，别在各自页面里再抄一遍。
 *
 * 数据全部来自系统 UsageEvents，不依赖 Rust 服务器。
 */
object DayFragments {

    private const val TAG = "DayFragments"

    /** 每个圆点的时间粒度：5 分钟 */
    const val SLOT_MIN = 5

    /** 一天的槽数：24 时 × 每时 12 槽 */
    const val SLOT_COUNT = 24 * 12

    private const val DAY_MS = 24 * 3600_000L

    /** 起床推断的凌晨截断：4 点前的深夜使用不计入「起床」 */
    private const val WAKE_CUTOFF_MS = 4 * 3600_000L

    /** 入睡归属的窗口延伸：事件查询延到次日中午，覆盖跨午夜会话的结束事件 */
    private const val SLEEP_WINDOW_MS = 12 * 3600_000L

    /** 事件查询向前多取一段：前夜开始的使用（跨午夜延续到当天）要能看到它的 RESUMED */
    private const val PRE_WINDOW_MS = 12 * 3600_000L

    /** 判成「睡了一觉」的最短空闲：短于它的算打盹/走开 */
    private const val SLEEP_GAP_MIN_MS = 2 * 3600_000L

    // UsageEvents 事件类型：API 29 改名（MOVE_TO_FOREGROUND→ACTIVITY_RESUMED 等）但数值不变；
    // minSdk 24 直接引用新常量会触发 lint NewApi，故用字面量
    private const val EVT_RESUMED = 1
    private const val EVT_PAUSED = 2
    private const val EVT_SCREEN_NON_INTERACTIVE = 16
    private const val EVT_STOPPED = 23

    /**
     * 单日扫描结果。
     *
     * @param slots 288 个 5 分钟槽的使用秒数，顺序 = 槽(0..11) × 时(0..23)，下标 slot*24+hour
     * @param launches 包名 → 当天启动次数（RESUMED 次数）
     * @param wakeMs 推断的起床时刻（真实毫秒时间戳），null = 无从推断
     * @param sleepMs 推断的入睡时刻（真实毫秒时间戳），null = 无从推断
     * @param totalSec 当天使用时长（秒）= slots 之和，并集口径，与系统「屏幕使用时间」一致
     */
    class Day(
        val slots: List<Double>,
        val launches: Map<String, Int>,
        val wakeMs: Long?,
        val sleepMs: Long?,
        val totalSec: Double,
    )

    /**
     * 扫描 [dayStartMs] 所在的本地自然日。
     *
     * @return 未授予「使用情况访问」/ 取不到 UsageStatsManager 时返回 null
     */
    fun scan(context: Context, dayStartMs: Long, nowMs: Long = System.currentTimeMillis()): Day? {
        if (!UsageStatsWatcher.isUsageAllowed(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val dayEnd = dayStartMs + DAY_MS
        // 向前多取一段：睡前那段使用在当天 0 点前就 RESUMED 了，只看当天会整段漏掉
        // （既算不出这段空档有多长，也会把「此刻还在用」当成入睡）
        val queryStart = dayStartMs - PRE_WINDOW_MS
        // 窗口延到次日中午：睡前的会话常跨过午夜（如 23:50–00:20），
        // PAUSED 落在次日，若把窗口掐在午夜就会把入睡错误地闭合成 23:59
        val endMs = minOf(dayEnd + SLEEP_WINDOW_MS, nowMs)

        val slotSec = DoubleArray(SLOT_COUNT)
        val launches = HashMap<String, Int>()
        // 已闭合的使用段（真实起止时刻，可含当天以外部分）：作息由段与段之间的空档推出
        val sessions = ArrayList<LongArray>()
        var openStart = -1L
        var openPkg: String? = null
        // 窗口里的第一个事件：用来认出「前一个会话在窗口之前就开始了」这种无头会话
        var firstEvent = true

        fun closeSession(end: Long) {
            val s = openStart
            openStart = -1L
            openPkg = null
            if (s < 0 || end <= s) return
            sessions.add(longArrayOf(s, end))
            // 热力只统计当天 0 点内的部分，跨午夜部分属于第二天
            addUsage(maxOf(s, dayStartMs), minOf(end, dayEnd), slotSec)
        }

        try {
            val ev = UsageEvents.Event()
            val events = usm.queryEvents(queryStart, endMs)
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val isFirst = firstEvent
                firstEvent = false
                when (ev.eventType) {
                    // 每次 App 到前台计一次启动（只算当天内的）
                    EVT_RESUMED -> {
                        val pkg = ev.packageName ?: continue
                        if (ev.timeStamp >= dayStartMs && ev.timeStamp < dayEnd) {
                            launches[pkg] = (launches[pkg] ?: 0) + 1
                        }
                        // 上一段立即闭合：同刻的包间切换因此不算空隙
                        if (openStart >= 0) closeSession(ev.timeStamp)
                        openStart = ev.timeStamp
                        openPkg = pkg
                    }
                    EVT_PAUSED, EVT_STOPPED -> {
                        if (openStart >= 0) {
                            // 只闭合**同包**的段：别的包暂停与我们无关（个别 ROM 会补发孤儿 PAUSED）
                            if (openPkg == ev.packageName) closeSession(ev.timeStamp)
                        } else if (isFirst && ev.eventType == EVT_PAUSED && ev.timeStamp > dayStartMs) {
                            // 窗口第一个事件就是 PAUSED：说明有个会话在查询窗口之前就开始了，
                            // 它的起止无从得知，用窗口起点占位即可——作息只看结束时刻，热力只取当天部分。
                            // （正常情况下的孤儿 PAUSED 都不是第一个事件，不会被误当成会话）
                            sessions.add(longArrayOf(queryStart, ev.timeStamp))
                            addUsage(dayStartMs, minOf(ev.timeStamp, dayEnd), slotSec)
                        }
                    }
                    // 息屏立即闭合：个别 ROM 息屏不发 PAUSED，不闭合会把息屏时间算进热力
                    EVT_SCREEN_NON_INTERACTIVE -> if (openStart >= 0) closeSession(ev.timeStamp)
                }
            }
            // 窗口末尾仍未闭合 = 此刻还在用：照样记一段，它后面没有空档，自然推不出入睡
            if (openStart >= 0) closeSession(endMs)
        } catch (e: Exception) {
            Log.w(TAG, "scan($dayStartMs) failed", e)
            return null
        }

        // 转成「行=5 分钟槽、列=小时」的点阵顺序
        val flat = ArrayList<Double>(SLOT_COUNT)
        for (s in 0 until 12) for (h in 0 until 24) flat.add(slotSec[h * 12 + s])

        val (sleepMs, wakeMs) = inferRest(sessions, dayStartMs, dayEnd)
        return Day(flat, launches, wakeMs, sleepMs, flat.sum())
    }

    /**
     * 从「空闲」推断作息：连续一段够长（≥ [SLEEP_GAP_MIN_MS]）没用手机就是睡了一觉——
     * 空档前一刻 = 入睡，空档后一刻 = 起床。
     *
     * - 入睡：空档起点落在当天 0 点 ~ 次日中午之间（含熬夜到次日白天才睡），取最长的那段；
     * - 起床：空档终点落在当天 4 点后 ~ 当天之内，取最长的那段（凌晨 4 点前的使用不算起床）。
     *
     * 窗口末尾仍在使用的会话后面没有空档，所以「此刻还在用手机」不会被算成入睡。
     */
    private fun inferRest(sessions: List<LongArray>, dayStartMs: Long, dayEnd: Long): Pair<Long?, Long?> {
        var sleepMs: Long? = null
        var wakeMs: Long? = null
        var longestSleepGap = 0L
        var longestWakeGap = 0L
        for (i in 0 until sessions.size - 1) {
            val prevEnd = sessions[i][1]
            val nextStart = sessions[i + 1][0]
            val gap = nextStart - prevEnd
            if (gap < SLEEP_GAP_MIN_MS) continue
            if (prevEnd >= dayStartMs && prevEnd < dayEnd + SLEEP_WINDOW_MS && gap > longestSleepGap) {
                longestSleepGap = gap
                sleepMs = prevEnd
            }
            if (nextStart >= dayStartMs + WAKE_CUTOFF_MS && nextStart < dayEnd && gap > longestWakeGap) {
                longestWakeGap = gap
                wakeMs = nextStart
            }
        }
        return sleepMs to wakeMs
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
}
