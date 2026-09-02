package net.activitywatch.android.dashboard

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

data class DashboardState(
    val loading: Boolean = false,
    val error: String? = null,
    val range: TimeRange = TimeRange.TODAY,
    val rangeLabel: String = "",
    /** 当前时间窗（Timeline 色带横轴用；ALL 时由实际数据两端决定） */
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    val totalTrackedSec: Double = 0.0,
    val activeSec: Double? = null,
    val afkSec: Double? = null,
    val apps: List<RankItem> = emptyList(),
    val websites: List<RankItem> = emptyList(),
    val buckets: List<BucketRow> = emptyList(),
    val segments: List<TimelineSegment> = emptyList(),
    val hours: List<HourSlot> = emptyList(),
    val trendDays: List<TrendDay> = emptyList(),
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
class DashboardViewModel : ViewModel() {
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

                val limit = if (range == TimeRange.ALL) 200_000L else 100_000L

                val appEvents = appBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }
                val webEvents = webBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }
                val afkEvents = afkBuckets.flatMap { safeFetch(it.id, startIso, endIso, limit) }

                // 颜色按「全区间总时长」的排名分配，保证跨 Tab 同色
                val colors = buildColorIndex(listOf(appEvents, webEvents), ::activityLabel)

                val apps = rank(appEvents, ::activityLabel, colors).take(10)
                val websites = rank(webEvents, ::websiteLabel, colors).take(10)

                val (activeSec, afkSec) = aggregateAfk(afkEvents)
                val totalTracked = appEvents.sumOf { it.duration }

                // Timeline 只吃应用事件：window watcher 与 web watcher 在同一时刻会重叠，
                // 混在一起画会出现互相覆盖的色块；没有应用数据时才回退到网站事件。
                val timelineSrc = appEvents.ifEmpty { webEvents }
                val timelineLabel: (EventDto) -> String? =
                    if (appEvents.isNotEmpty()) ::activityLabel else ::websiteLabel

                val segments = buildSegments(timelineSrc, timelineLabel, colors)
                val hours = buildHours(timelineSrc, timelineLabel, colors)
                val trendDays = buildTrendDays(timelineSrc, timelineLabel, afkEvents, colors)

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
                        activeSec = activeSec,
                        afkSec = afkSec,
                        apps = apps,
                        websites = websites,
                        buckets = bucketRows,
                        segments = segments,
                        hours = hours,
                        trendDays = trendDays,
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

    /** 时长前 RANKED 名的标签各自拿一个颜色，其余统一灰色。 */
    private fun buildColorIndex(
        eventGroups: List<List<EventDto>>,
        label: (EventDto) -> String?,
    ): Map<String, Int> {
        val totals = LinkedHashMap<String, Double>()
        for (group in eventGroups) {
            for (e in group) {
                val key = label(e) ?: continue
                totals[key] = (totals[key] ?: 0.0) + e.duration
            }
        }
        return totals.entries.sortedByDescending { it.value }
            .take(ActivityPalette.RANKED)
            .mapIndexed { index, entry -> entry.key to index }
            .toMap()
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

    /** 按自然日分桶，同时把 AFK 事件的专注/闲置时长摊到每一天。 */
    private fun buildTrendDays(
        events: List<EventDto>,
        label: (EventDto) -> String?,
        afkEvents: List<EventDto>,
        colors: Map<String, Int>,
        topN: Int = 3,
    ): List<TrendDay> {
        val totals = LinkedHashMap<Long, Double>()
        val perLabel = LinkedHashMap<Long, LinkedHashMap<String, Double>>()
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
            TimeRange.LAST7 -> Pair(now - 7 * 24 * 3600_000L, now)
            TimeRange.LAST30 -> Pair(now - 30L * 24 * 3600_000L, now)
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
            TimeRange.LAST7 -> {
                val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -7) }
                Pair(fmt.format(start.time), endIso)
            }
            TimeRange.LAST30 -> {
                val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }
                Pair(fmt.format(start.time), endIso)
            }
            TimeRange.ALL -> Pair(null, null)
        }
    }
}
