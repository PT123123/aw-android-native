package net.activitywatch.android.dashboard

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.activitywatch.android.widget.ScreenTimeStats
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "DashboardViewModel"

/** 单个排行项：标签、时长（秒）、占比（0..1，用于画进度条）、分类色索引。 */
data class RankItem(
    val label: String,
    val durationSec: Double,
    val ratio: Float,
    val colorIndex: Int = ActivityPalette.OTHER,
)

data class BucketRow(
    val id: String,
    val type: String?,
    val lastUpdated: String?,
    val aggregated: Boolean,
)

/** 时间线上的一段连续活动（相邻同类事件已合并）。 */
data class TimelineSegment(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val colorIndex: Int,
)

/** 按自然小时聚合出的一格，items 为该小时内时长最高的几项。 */
data class HourSlot(
    val hourStartMs: Long,
    val totalSec: Double,
    val items: List<RankItem>,
)

/** 按自然日聚合出的一条趋势数据。 */
data class TrendDay(
    val dayStartMs: Long,
    val totalSec: Double,
    val activeSec: Double,
    val afkSec: Double,
    val items: List<RankItem>,
)

/** 一条「泳道」：某个数据桶在整段时间窗内的着色段集合。priority 越小合并时越优先。 */
data class BucketTimeline(
    val id: String,
    val displayName: String,
    val priority: Int,
    val segments: List<TimelineSegment>,
)

data class DashboardState(
    val loading: Boolean = false,
    val error: String? = null,
    val range: TimeRange = TimeRange.TODAY,
    val rangeLabel: String = "",
    /** 当前时间窗（Timeline 色带横轴用；ALL 时由实际数据两端决定） */
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val totalTrackedSec: Double = 0.0,
    /** 聚合（系统口径）总屏幕时长（秒）。null = 未授予「使用情况访问」权限或查询失败 */
    val sysTotalSec: Double? = null,
    val activeSec: Double? = null,
    val afkSec: Double? = null,
    val apps: List<RankItem> = emptyList(),
    val websites: List<RankItem> = emptyList(),
    val buckets: List<BucketRow> = emptyList(),
    val segments: List<TimelineSegment> = emptyList(),
    val hours: List<HourSlot> = emptyList(),
    val trendDays: List<TrendDay> = emptyList(),
    /** 各数据桶独立的时间线泳道（应用 / 网页 / 离开 / 秒表…），空桶不列出。 */
    val bucketTimelines: List<BucketTimeline> = emptyList(),
)

enum class TimeRange(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST7("近 7 天"),
    LAST30("近 30 天"),
    ALL("全部"),
}

/**
 * 活动（Activity）页三个 Tab 共享的数据源。
 *
 * 由宿主 DashboardFragment 持有（ViewModelProvider(this)），
 * 概览 / 时间线 / 趋势 三个子 Fragment 通过 parentFragment 取到同一实例，
 * 因此切换时间范围只拉取一次事件，三个视图同时刷新。
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var currentRange: TimeRange = TimeRange.TODAY
    private var retried = false

    init {
        load(currentRange)
    }

    fun reload() = load(currentRange)

    fun load(range: TimeRange) {
        currentRange = range
        retried = false
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null, range = range, rangeLabel = range.label) }
            try {
                ActivityApi.init()
                val (startIso, endIso) = range.toIso()
                val buckets = ActivityApi.service.getBuckets()

                val appBuckets = buckets.values.filter { isAppUsage(it) }
                val webBuckets = buckets.values.filter { isWeb(it) }
                val afkBuckets = buckets.values.filter { isAfk(it) }
                val stopBuckets = buckets.values.filter { isStopwatch(it) }

                val limit = if (range == TimeRange.ALL) 200_000L else 100_000L

                val appEvents = appBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }
                val webEvents = webBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }
                val afkEvents = afkBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }
                val stopEvents = stopBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }

                // 聚合（系统口径）数据：直读 UsageStatsManager，不受采集进程存活影响
                val sysPermitted = UsageStatsWatcher.isUsageAllowed(appContext)
                val sysDaily = if (sysPermitted) querySysUsage(range) else emptyList()
                val sysPerPkgSec = sysPerLabelSec(sysDaily, appContext.packageManager)
                // 「屏幕时长」总量走事件并集（同一时刻只算一次，与系统「屏幕使用时间」同口径）；
                // 逐包 totalTimeInForeground 只用来出逐包榜单——它会把同时处于前台的多个包重复累加
                val sysUnionByDay = if (sysPermitted) querySysUnion(range) else emptyMap()
                val sysTotalsByDay = sysDayTotals(sysDaily, sysUnionByDay)
                val sysTotalSec: Double? = if (sysPermitted) sysTotalsByDay.values.sum() else null

                // 颜色按「全区间总时长」的排名分配，保证跨 Tab 同色；秒表标签也参与排名；
                // 聚合榜单的标签一并参与，漏采-only 的应用也能拿到与时间线一致的颜色
                val colors = buildColorIndex(
                    listOf(
                        labelTotals(appEvents, ::activityLabel),
                        labelTotals(webEvents, ::websiteLabel),
                        labelTotals(stopEvents, ::stopwatchLabel),
                        sysPerPkgSec,
                    )
                )

                // 概览/趋势的 App 榜单：聚合（系统口径）优先，未授权/无数据时回退事件流榜单
                val apps = buildSysRank(sysPerPkgSec, colors)
                    .ifEmpty { rank(appEvents, ::activityLabel, colors).take(10) }
                val websites = rank(webEvents, ::websiteLabel, colors).take(10)

                val (activeSec, afkSec) = aggregateAfk(afkEvents)
                val totalTracked = appEvents.sumOf { it.duration }

                // 概览 / 按小时 / 趋势仍按时间线标签页口径：应用事件优先，没有才回退到网站事件
                val timelineSrc = appEvents.ifEmpty { webEvents }
                val timelineLabel: (EventDto) -> String? =
                    if (appEvents.isNotEmpty()) ::activityLabel else ::websiteLabel

                val hours = buildHours(timelineSrc, timelineLabel, colors)
                val trendDays = buildTrendDays(
                    sysDaily, sysUnionByDay, timelineSrc, timelineLabel, afkEvents, colors, appContext.packageManager
                )

                // 分桶时间线：每个桶各自一条泳道；合并时按优先级（应用>网页>离开>秒表）解决重叠
                val afkColors = mapOf(
                    "离开" to ActivityPalette.AFK_AWAY,
                    "活跃" to ActivityPalette.AFK_ACTIVE,
                )
                val lanes = listOfNotNull(
                    bucketLane("app", "应用", 1, appEvents, ::activityLabel, colors),
                    bucketLane("web", "网页", 2, webEvents, ::websiteLabel, colors),
                    bucketLane("afk", "离开", 3, afkEvents, ::afkLabel, afkColors),
                    bucketLane("stopwatch", "秒表", 4, stopEvents, ::stopwatchLabel, colors),
                )
                val segments = mergeLanes(lanes.map { it.priority to it.segments })

                val window = range.windowMs(segments)

                val bucketRows = buckets.values.map { b ->
                    BucketRow(
                        id = b.id,
                        type = b.type,
                        lastUpdated = b.last_updated,
                        aggregated = isAppUsage(b) || isWeb(b) || isAfk(b),
                    )
                }.sortedWith(compareBy({ !it.aggregated }, { it.id }))

                _state.update {
                    it.copy(
                        loading = false,
                        totalTrackedSec = totalTracked,
                        sysTotalSec = sysTotalSec,
                        activeSec = activeSec,
                        afkSec = afkSec,
                        apps = apps,
                        websites = websites,
                        buckets = bucketRows,
                        segments = segments,
                        hours = hours,
                        trendDays = trendDays,
                        bucketTimelines = lanes,
                        windowStartMs = window.first,
                        windowEndMs = window.second,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "load($range) failed", e)
                // 服务器可能还没起好（连接被拒）：1.5s 后重试一次，避免首开误报失败
                if (!retried && e is IOException) {
                    retried = true
                    Log.w(TAG, "load($range) retrying once after delay (server likely not ready)")
                    delay(1500)
                    load(range)
                    return@launch
                }
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    // ---------- 聚合 ----------

    private suspend fun safeFetch(bucketId: String, start: String?, end: String?, limit: Long): List<EventDto> {
        return try {
            ActivityApi.service.getEvents(bucketId, start, end, limit)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $bucketId: ${e.message}")
            emptyList()
        }
    }

    /** 按标签聚合出降序榜单，并附带颜色索引。 */
    private fun rank(
        events: List<EventDto>,
        label: (EventDto) -> String?,
        colors: Map<String, Int>,
    ): List<RankItem> {
        val map = LinkedHashMap<String, Double>()
        for (e in events) {
            val key = label(e) ?: continue
            map[key] = (map[key] ?: 0.0) + e.duration
        }
        val sorted = map.entries.sortedByDescending { it.value }
        val max = sorted.firstOrNull()?.value ?: 1.0
        return sorted.map { (k, v) ->
            RankItem(k, v, (v / max).toFloat().coerceIn(0f, 1f), colors[k] ?: ActivityPalette.OTHER)
        }
    }

    /** 时长前 RANKED 名的标签各自拿一个颜色，其余统一灰色。输入为各数据源的 label→秒数聚合。 */
    private fun buildColorIndex(groups: List<Map<String, Double>>): Map<String, Int> {
        val totals = LinkedHashMap<String, Double>()
        for (group in groups) {
            for ((key, sec) in group) {
                totals[key] = (totals[key] ?: 0.0) + sec
            }
        }
        return totals.entries.sortedByDescending { it.value }
            .take(ActivityPalette.RANKED)
            .mapIndexed { index, entry -> entry.key to index }
            .toMap()
    }

    /** 事件流按 label 聚合出秒数表（颜色排名用）。 */
    private fun labelTotals(events: List<EventDto>, label: (EventDto) -> String?): Map<String, Double> {
        val map = LinkedHashMap<String, Double>()
        for (e in events) {
            val key = label(e) ?: continue
            map[key] = (map[key] ?: 0.0) + e.duration
        }
        return map
    }

    // ---------- 聚合（系统口径） ----------

    /**
     * 当前范围的聚合按天数据：直读 UsageStatsManager 的 totalTimeInForeground。
     * 未授权/异常返回空列表，调用方回退事件流口径。
     */
    private fun querySysUsage(range: TimeRange): List<ScreenTimeStats.DailyUsage> {
        return try {
            val (startMs, endMs) = rangeWindowMs(range)
            val daily = ScreenTimeStats.queryDailyUsage(appContext, startMs, endMs)
            // TODAY/YESTERDAY 只取对应自然日；LAST7/LAST30 取含今天的 N 个自然日；ALL 取全部日桶
            val minDay = when (range) {
                TimeRange.TODAY -> startOfDayMs(System.currentTimeMillis())
                TimeRange.YESTERDAY -> startOfDayMs(System.currentTimeMillis()) - 24 * 3600_000L
                TimeRange.LAST7 -> startOfDayMs(System.currentTimeMillis()) - 6 * 24 * 3600_000L
                TimeRange.LAST30 -> startOfDayMs(System.currentTimeMillis()) - 29 * 24 * 3600_000L
                TimeRange.ALL -> null
            }
            if (minDay == null) daily else daily.filter { it.dayStartMs >= minDay }
        } catch (e: Exception) {
            Log.w(TAG, "querySysUsage failed", e)
            emptyList()
        }
    }

    /**
     * 逐日「屏幕使用时长」（并集口径，秒）：同一时刻只算一次，与系统设置的
     * 「屏幕使用时间」同口径。逐包 totalTimeInForeground 会把同时处于前台的
     * 多个包重复累加（本机实测虚高约 20%），因此只用来出逐包榜单、不参与总量。
     *
     * 系统的事件只保留最近 [ScreenTimeStats.EVENT_LOOKBACK_DAYS] 天左右，
     * 更早的日子查不到事件，由调用方回退逐包求和。
     */
    private fun querySysUnion(range: TimeRange): Map<Long, Double> {
        return try {
            val (startMs, endMs) = rangeWindowMs(range)
            val from = maxOf(startMs, ScreenTimeStats.unionEarliestMs())
            ScreenTimeStats.queryDailyUnion(appContext, ScreenTimeStats.startOfDayMs(from), endMs)
                .mapValues { (_, ms) -> ms / 1000.0 }
        } catch (e: Exception) {
            Log.w(TAG, "querySysUnion failed", e)
            emptyMap()
        }
    }

    /**
     * 逐日总量（秒）：并集优先；没有并集的日子（超出事件保留期）回退逐包求和。
     * 两边的日子取并集，趋势图里每个有数据的日子都不缺格。
     */
    private fun sysDayTotals(
        sysDaily: List<ScreenTimeStats.DailyUsage>,
        unionByDay: Map<Long, Double>,
    ): Map<Long, Double> {
        val out = LinkedHashMap<Long, Double>()
        for (d in sysDaily) out[d.dayStartMs] = d.perPkgMs.values.sum() / 1000.0
        for ((day, sec) in unionByDay) out[day] = sec
        return out
    }

    /** 聚合按天数据按应用名合并成 label→秒数表（包名先解析成应用名，同标签跨包合并）。 */
    private fun sysPerLabelSec(
        sysDaily: List<ScreenTimeStats.DailyUsage>,
        pm: PackageManager,
    ): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (d in sysDaily) {
            for ((pkg, ms) in d.perPkgMs) {
                val label = ScreenTimeStats.resolveLabel(pm, pkg)
                out[label] = (out[label] ?: 0.0) + ms / 1000.0
            }
        }
        return out
    }

    /** 聚合口径的 App 榜单：按秒数降序取 Top 10，ratio 按最大值归一。 */
    private fun buildSysRank(perLabelSec: Map<String, Double>, colors: Map<String, Int>): List<RankItem> {
        if (perLabelSec.isEmpty()) return emptyList()
        val sorted = perLabelSec.entries.sortedByDescending { it.value }
        val max = sorted.first().value
        return sorted.take(10).map { (label, sec) ->
            RankItem(label, sec, (sec / max).toFloat().coerceIn(0f, 1f), colors[label] ?: ActivityPalette.OTHER)
        }
    }

    private data class RawSegment(val start: Long, val end: Long, val label: String)

    /**
     * 把事件流压成连续的着色段：先按开始时间排序，再把相邻同标签的段合并。
     * 段数超过上限时指数增大合并间隙（1s → 4s → 16s …），
     * 避免几万条事件直接丢给自定义 View 去画矩形。
     */
    private fun buildSegments(
        events: List<EventDto>,
        label: (EventDto) -> String?,
        colors: Map<String, Int>,
        maxSegments: Int = 4000,
    ): List<TimelineSegment> {
        val raws = ArrayList<RawSegment>(events.size)
        for (e in events) {
            val l = label(e) ?: continue
            val start = parseIsoToMillis(e.timestamp) ?: continue
            val durMs = (e.duration * 1000.0).toLong()
            if (durMs <= 0) continue
            raws.add(RawSegment(start, start + durMs, l))
        }
        raws.sortBy { it.start }

        var gapMs = 1_000L
        var merged = mergeAdjacent(raws, gapMs)
        while (merged.size > maxSegments && gapMs < 600_000L) {
            gapMs *= 4
            merged = mergeAdjacent(raws, gapMs)
        }
        return merged.map {
            TimelineSegment(it.start, it.end, it.label, colors[it.label] ?: ActivityPalette.OTHER)
        }
    }

    private fun mergeAdjacent(raws: List<RawSegment>, gapMs: Long): List<RawSegment> {
        val out = ArrayList<RawSegment>(raws.size)
        for (r in raws) {
            val last = out.lastOrNull()
            if (last != null && last.label == r.label && r.start - last.end <= gapMs) {
                out[out.size - 1] = RawSegment(last.start, maxOf(last.end, r.end), last.label)
            } else {
                out.add(r)
            }
        }
        return out
    }

    /** 按自然小时分桶；跨小时的长事件按时长切分到各小时，避免整段记在起始小时。 */
    private fun buildHours(
        events: List<EventDto>,
        label: (EventDto) -> String?,
        colors: Map<String, Int>,
        topN: Int = 3,
    ): List<HourSlot> {
        val totals = LinkedHashMap<Long, Double>()
        val perLabel = LinkedHashMap<Long, LinkedHashMap<String, Double>>()
        for (e in events) {
            val l = label(e) ?: continue
            val start = parseIsoToMillis(e.timestamp) ?: continue
            if (e.duration <= 0) continue
            forEachHourSlot(start, e.duration) { hourStart, sec ->
                totals[hourStart] = (totals[hourStart] ?: 0.0) + sec
                val bucket = perLabel.getOrPut(hourStart) { LinkedHashMap() }
                bucket[l] = (bucket[l] ?: 0.0) + sec
            }
        }
        return totals.keys.sorted().map { h ->
            val total = totals[h] ?: 0.0
            val items = perLabel[h]?.entries
                ?.sortedByDescending { it.value }
                ?.take(topN)
                ?.map { (k, v) ->
                    val ratio = if (total > 0) (v / total).toFloat().coerceIn(0f, 1f) else 0f
                    RankItem(k, v, ratio, colors[k] ?: ActivityPalette.OTHER)
                }
                .orEmpty()
            HourSlot(h, total, items)
        }
    }

    /**
     * 按自然日出趋势数据：每日总量走事件并集（与系统「屏幕使用时间」一致，
     * 没有并集数据的日子回退逐包求和），Top 应用用聚合（系统口径）数据；
     * 未授权/无数据时回退事件流。专注/闲置（AFK）始终由事件流 AFK 事件摊到每天。
     */
    private fun buildTrendDays(
        sysDaily: List<ScreenTimeStats.DailyUsage>,
        unionByDay: Map<Long, Double>,
        events: List<EventDto>,
        label: (EventDto) -> String?,
        afkEvents: List<EventDto>,
        colors: Map<String, Int>,
        pm: PackageManager,
        topN: Int = 3,
    ): List<TrendDay> {
        val totals = LinkedHashMap<Long, Double>()
        val perLabel = LinkedHashMap<Long, LinkedHashMap<String, Double>>()
        if (sysDaily.isNotEmpty()) {
            for (d in sysDaily) {
                val bucket = perLabel.getOrPut(d.dayStartMs) { LinkedHashMap() }
                for ((pkg, ms) in d.perPkgMs) {
                    val l = ScreenTimeStats.resolveLabel(pm, pkg)
                    bucket[l] = (bucket[l] ?: 0.0) + ms / 1000.0
                }
                // 逐包求和会把并发前台的包重复计时，有并集就用并集
                totals[d.dayStartMs] = unionByDay[d.dayStartMs] ?: bucket.values.sum()
            }
            // 并集覆盖到、UsageStats 却没返回的日子（少见）也补上，趋势不缺格
            for ((day, sec) in unionByDay) {
                if (day !in totals) {
                    totals[day] = sec
                    perLabel.getOrPut(day) { LinkedHashMap() }
                }
            }
        } else {
            for (e in events) {
                val l = label(e) ?: continue
                val start = parseIsoToMillis(e.timestamp) ?: continue
                if (e.duration <= 0) continue
                forEachDaySlot(start, e.duration) { dayStart, sec ->
                    totals[dayStart] = (totals[dayStart] ?: 0.0) + sec
                    val bucket = perLabel.getOrPut(dayStart) { LinkedHashMap() }
                    bucket[l] = (bucket[l] ?: 0.0) + sec
                }
            }
        }

        val activeByDay = LinkedHashMap<Long, Double>()
        val afkByDay = LinkedHashMap<Long, Double>()
        for (e in afkEvents) {
            val start = parseIsoToMillis(e.timestamp) ?: continue
            if (e.duration <= 0) continue
            val isAfk = strOf(e.data, "status")?.lowercase() == "afk"
            forEachDaySlot(start, e.duration) { dayStart, sec ->
                val target = if (isAfk) afkByDay else activeByDay
                target[dayStart] = (target[dayStart] ?: 0.0) + sec
            }
        }

        val days = (totals.keys + activeByDay.keys + afkByDay.keys).toSortedSet()
        return days.map { d ->
            val total = totals[d] ?: 0.0
            val items = perLabel[d]?.entries
                ?.sortedByDescending { it.value }
                ?.take(topN)
                ?.map { (k, v) ->
                    val ratio = if (total > 0) (v / total).toFloat().coerceIn(0f, 1f) else 0f
                    RankItem(k, v, ratio, colors[k] ?: ActivityPalette.OTHER)
                }
                .orEmpty()
            TrendDay(
                dayStartMs = d,
                totalSec = total,
                activeSec = activeByDay[d] ?: 0.0,
                afkSec = afkByDay[d] ?: 0.0,
                items = items,
            )
        }
    }

    private fun aggregateAfk(events: List<EventDto>): Pair<Double?, Double?> {
        if (events.isEmpty()) return null to null
        var active = 0.0
        var afk = 0.0
        for (e in events) {
            when (strOf(e.data, "status")?.lowercase()) {
                "afk" -> afk += e.duration
                "not-afk" -> active += e.duration
                else -> active += e.duration // 未知状态也计入活跃，避免总时长凭空丢失
            }
        }
        return active to afk
    }

    // ---------- 分桶泳道 ----------

    /** 把某个桶的事件压成一条泳道；事件为空则返回 null（不展示该桶）。 */
    private fun bucketLane(
        id: String,
        name: String,
        priority: Int,
        events: List<EventDto>,
        label: (EventDto) -> String?,
        colors: Map<String, Int>,
    ): BucketTimeline? {
        if (events.isEmpty()) return null
        return BucketTimeline(id, name, priority, buildSegments(events, label, colors))
    }

    /**
     * 把多条泳道按时间合并成一条：在任意时刻取优先级最高（priority 最小）的桶的段。
     * 用于「合并展示」——应用与网页同一时刻重叠时应用优先，离开时段则露出 AFK 段。
     */
    private fun mergeLanes(lanes: List<Pair<Int, List<TimelineSegment>>>): List<TimelineSegment> {
        data class Pt(val ms: Long, val type: Int, val prio: Int, val seg: TimelineSegment)
        val pts = ArrayList<Pt>()
        for ((prio, segs) in lanes) {
            for (s in segs) {
                pts.add(Pt(s.startMs, 1, prio, s))
                pts.add(Pt(s.endMs, -1, prio, s))
            }
        }
        if (pts.isEmpty()) return emptyList()
        // 同一时刻先处理「结束」再处理「开始」，避免相邻段之间出现空隙
        pts.sortWith(compareBy({ it.ms }, { it.type }, { it.prio }))

        val active = LinkedHashMap<Int, MutableList<TimelineSegment>>()
        val out = ArrayList<TimelineSegment>()
        var prevMs: Long? = null
        var curTop: TimelineSegment? = null
        for (p in pts) {
            if (p.type == 1) {
                active.getOrPut(p.prio) { ArrayList() }.add(p.seg)
            } else {
                active[p.prio]?.remove(p.seg)
            }
            val newTop = active.keys.minOrNull()?.let { active[it]!!.lastOrNull() }
            if (newTop !== curTop) {
                if (prevMs != null && curTop != null && p.ms > prevMs) {
                    out.add(TimelineSegment(prevMs, p.ms, curTop.label, curTop.colorIndex))
                }
                curTop = newTop
                prevMs = p.ms
            }
        }
        return out
    }

    // ---------- 标签与类型判定 ----------

    private fun activityLabel(e: EventDto): String? = strOf(e.data, "app")?.ifBlank { null }

    private fun websiteLabel(e: EventDto): String? =
        hostOf(strOf(e.data, "url")) ?: strOf(e.data, "app")

    private fun strOf(json: JsonObject?, key: String): String? {
        val el = json?.get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val h = Uri.parse(url).host
            h?.removePrefix("www.")?.ifBlank { null } ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun isAppUsage(b: BucketInfo): Boolean {
        val t = (b.type ?: "").lowercase()
        val id = b.id.lowercase()
        return t.contains("window") || t.contains("androidapp") || t.contains("currentwindow") ||
            id.contains("aw-watcher-android") || id.contains("aw-watcher-window")
    }

    private fun isWeb(b: BucketInfo): Boolean {
        val t = (b.type ?: "").lowercase()
        val id = b.id.lowercase()
        return t.contains("web") || id.contains("aw-watcher-web") || id.contains("web-chrome")
    }

    private fun isAfk(b: BucketInfo): Boolean {
        val t = (b.type ?: "").lowercase()
        val id = b.id.lowercase()
        return t.contains("afk") || id.contains("aw-watcher-afk")
    }

    private fun isStopwatch(b: BucketInfo): Boolean {
        val t = (b.type ?: "").lowercase()
        val id = b.id.lowercase()
        return t.contains("stopwatch") || id.contains("aw-stopwatch-android") || id.contains("stopwatch")
    }

    /** AFK 桶的标签：离开 / 活跃（未知状态按活跃计）。 */
    private fun afkLabel(e: EventDto): String? {
        val status = strOf(e.data, "status")?.lowercase()
        return if (status == "afk") "离开" else "活跃"
    }

    /** 秒表桶的标签：取记录时填的名称，没有则兜底「手动记录」。 */
    private fun stopwatchLabel(e: EventDto): String? =
        strOf(e.data, "label")?.ifBlank { null } ?: "手动记录"

    // ---------- 时间窗 ----------

    /**
     * Timeline 色带的横轴范围。ALL 没有固定边界，就用实际数据的首尾；
     * 其余范围按本地日历算出起止，数据为空时也能画出完整的刻度。
     */
    private fun TimeRange.windowMs(segments: List<TimelineSegment>): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        return when (this) {
            TimeRange.TODAY -> Pair(startOfDayMs(now), now)
            TimeRange.YESTERDAY -> {
                val todayStart = startOfDayMs(now)
                Pair(startOfDayMs(todayStart - 24 * 3600_000L), todayStart)
            }
            // 与聚合口径一致：按自然日（含今天）的 N 天
            TimeRange.LAST7, TimeRange.LAST30 -> rangeWindowMs(this)
            TimeRange.ALL -> {
                val min = segments.minByOrNull { it.startMs }?.startMs
                val max = segments.maxByOrNull { it.endMs }?.endMs
                if (min == null || max == null || max <= min) Pair(null, null)
                else Pair(min, max)
            }
        }
    }

    /**
     * 计算时间窗的 ISO-8601 字符串（含本地时区偏移，如 2026-09-02T00:00:00+08:00）。
     * 用 java.util.Calendar + SimpleDateFormat 而非 threetenbp：后者需要先调用
     * AndroidThreeTen.init() 注册时区库，否则 ZoneId/LocalDate.now 会抛
     * "No timezone data files registered"。本项目仅此处在意时区，故直接走系统 Calendar，
     * 与 InboxAdapter 的 SimpleDateFormat("...XXX") 写法保持一致，aw-server 的 chrono 可正常解析。
     */
    private fun TimeRange.toIso(): Pair<String?, String?> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val endIso = fmt.format(Calendar.getInstance().time)
        return when (this) {
            TimeRange.TODAY -> {
                val start = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                Pair(fmt.format(start.time), endIso)
            }
            TimeRange.YESTERDAY -> {
                val start = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val end = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                Pair(fmt.format(start.time), fmt.format(end.time))
            }
            // 与聚合口径一致：按自然日（含今天）的 N 天
            TimeRange.LAST7, TimeRange.LAST30 -> Pair(isoOf(rangeWindowMs(this).first), endIso)
            TimeRange.ALL -> Pair(null, null)
        }
    }
}
