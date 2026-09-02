package net.activitywatch.android.dashboard

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.ZoneId
import org.threeten.bp.temporal.ChronoUnit

private const val TAG = "DashboardViewModel"

/** 单个排行项：标签、时长（秒）、相对最高项的比例（0..1，用于画进度条）。 */
data class RankItem(
    val label: String,
    val durationSec: Double,
    val ratio: Float,
)

data class BucketRow(
    val id: String,
    val type: String?,
    val lastUpdated: String?,
    val aggregated: Boolean,
)

data class DashboardState(
    val loading: Boolean = false,
    val error: String? = null,
    val rangeLabel: String = "",
    val totalTrackedSec: Double = 0.0,
    val activeSec: Double? = null,
    val afkSec: Double? = null,
    val apps: List<RankItem> = emptyList(),
    val websites: List<RankItem> = emptyList(),
    val buckets: List<BucketRow> = emptyList(),
)

enum class TimeRange(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST7("近 7 天"),
    LAST30("近 30 天"),
    ALL("全部"),
}

/**
 * 原生仪表盘的状态与聚合逻辑，替代被移除的 WebUIFragment（aw-webui）。
 * 直接在客户端拉取 events 后按 app/status 聚合，不依赖服务端 query 语言。
 */
class DashboardViewModel : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var currentRange: TimeRange = TimeRange.TODAY

    init {
        load(currentRange)
    }

    fun reload() = load(currentRange)

    fun load(range: TimeRange) {
        currentRange = range
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null, rangeLabel = range.label) }
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

                val apps = aggregate(appEvents) { strOf(it.data, "app") }.take(10)
                val websites = aggregate(webEvents) { hostOf(strOf(it.data, "url")) ?: strOf(it.data, "app") }.take(10)

                val (activeSec, afkSec) = aggregateAfk(afkEvents)
                val totalTracked = appEvents.sumOf { it.duration }

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
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "load($range) failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun safeFetch(bucketId: String, start: String?, end: String?, limit: Long): List<EventDto> {
        return try {
            ActivityApi.service.getEvents(bucketId, start, end, limit)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $bucketId: ${e.message}")
            emptyList()
        }
    }

    private fun aggregate(events: List<EventDto>, keyExtractor: (EventDto) -> String?): List<RankItem> {
        val map = LinkedHashMap<String, Double>()
        for (e in events) {
            val key = keyExtractor(e) ?: continue
            if (key.isBlank()) continue
            map[key] = (map[key] ?: 0.0) + e.duration
        }
        val sorted = map.entries.sortedByDescending { it.value }
        val max = sorted.firstOrNull()?.value ?: 1.0
        return sorted.map { (k, v) ->
            RankItem(k, v, (v / max).toFloat().coerceIn(0f, 1f))
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

    private fun TimeRange.toIso(): Pair<String?, String?> {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return when (this) {
            TimeRange.TODAY ->
                Pair(LocalDate.now(zone).atStartOfDay(zone).toInstant().toString(), now.toString())
            TimeRange.YESTERDAY -> {
                val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val yStart = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant()
                Pair(yStart.toString(), todayStart.toString())
            }
            TimeRange.LAST7 ->
                Pair(now.minus(7, ChronoUnit.DAYS).toString(), now.toString())
            TimeRange.LAST30 ->
                Pair(now.minus(30, ChronoUnit.DAYS).toString(), now.toString())
            TimeRange.ALL ->
                Pair(null, null)
        }
    }
}
