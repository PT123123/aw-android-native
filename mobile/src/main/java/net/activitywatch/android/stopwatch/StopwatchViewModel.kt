package net.activitywatch.android.stopwatch

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.activitywatch.android.dashboard.ActivityApi
import net.activitywatch.android.dashboard.CreateBucketRequest
import net.activitywatch.android.dashboard.EventDto
import net.activitywatch.android.dashboard.formatDuration
import net.activitywatch.android.dashboard.isoOf
import net.activitywatch.android.dashboard.parseIsoToMillis
import java.io.IOException

private const val TAG = "StopwatchViewModel"

data class StopwatchRecord(
    val startMs: Long,
    val durationSec: Double,
    val label: String,
)

/**
 * 秒表状态与写入逻辑。
 *
 * 计时本身用「累计值 + 本次起点」的方式算，不依赖任何定时器，
 * 所以界面 destroy / 重建都不会影响读数；UI 只负责按固定间隔读 currentMs()。
 * 停止时把一段 [起, 止) 的事件 POST 到本机 aw-server 的 stopwatch bucket。
 */
class StopwatchViewModel : ViewModel() {

    private var startWallMs = 0L
    private var accumulatedMs = 0L
    private var ticking = false

    private val _records = MutableStateFlow<List<StopwatchRecord>>(emptyList())
    val records: StateFlow<List<StopwatchRecord>> = _records.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val isRunning: Boolean get() = ticking

    init {
        refresh()
    }

    /** 当前读数（毫秒）。 */
    fun currentMs(): Long = accumulatedMs + if (ticking) System.currentTimeMillis() - startWallMs else 0L

    fun start() {
        if (ticking) return
        startWallMs = System.currentTimeMillis()
        ticking = true
    }

    fun pause() {
        if (!ticking) return
        accumulatedMs += System.currentTimeMillis() - startWallMs
        ticking = false
    }

    fun reset() {
        ticking = false
        startWallMs = 0L
        accumulatedMs = 0L
    }

    /** 取走一次性提示（写入成功/失败）。 */
    fun consumeStatus() {
        _status.value = null
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ActivityApi.init()
                val events = try {
                    ActivityApi.service.getEvents(BUCKET_ID, null, null, RECORD_LIMIT)
                } catch (e: Exception) {
                    // bucket 还没建过，第一次用时属正常情况
                    Log.i(TAG, "no stopwatch bucket yet: ${e.message}")
                    emptyList()
                }
                _records.value = events.mapNotNull { e ->
                    val start = parseIsoToMillis(e.timestamp) ?: return@mapNotNull null
                    StopwatchRecord(start, e.duration, labelOf(e) ?: "（无名称）")
                }.sortedByDescending { it.startMs }
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed", e)
            }
        }
    }

    /** 停止并把这一段计时写入服务端；不足 1 秒直接丢弃。 */
    fun save(rawLabel: String) {
        val durMs = currentMs()
        if (durMs < MIN_SAVE_MS) {
            _status.value = "不足 1 秒，未记录"
            return
        }
        val label = rawLabel.ifBlank { "手动记录" }
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                ActivityApi.init()
                ensureBucket()
                val end = System.currentTimeMillis()
                val data = JsonObject().apply { addProperty("label", label) }
                val event = EventDto(
                    timestamp = isoOf(end - durMs),
                    duration = durMs / 1000.0,
                    data = data,
                )
                val resp = ActivityApi.service.createEvents(BUCKET_ID, listOf(event))
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
                reset()
                _status.value = "已记录 $label · ${formatDuration(durMs / 1000.0)}"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                _status.value = "记录失败：${e.message}"
            } finally {
                _saving.value = false
            }
        }
    }

    /** bucket 不存在时先建一个；已存在就跳过（重复创建服务端会报错）。 */
    private suspend fun ensureBucket() {
        try {
            ActivityApi.service.getBucket(BUCKET_ID)
            return
        } catch (_: Exception) {
            // 404 => 需要创建
        }
        val resp = ActivityApi.service.createBucket(
            BUCKET_ID,
            CreateBucketRequest(id = BUCKET_ID, type = BUCKET_TYPE, client = CLIENT_NAME)
        )
        if (!resp.isSuccessful && resp.code() != 304) {
            throw IOException("创建 bucket 失败：HTTP ${resp.code()}")
        }
    }

    private fun labelOf(e: EventDto): String? {
        val el = e.data?.get("label") ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
    }

    companion object {
        const val BUCKET_ID = "aw-stopwatch-android"
        private const val BUCKET_TYPE = "stopwatch"
        private const val CLIENT_NAME = "aw-android"
        private const val RECORD_LIMIT = 50L
        private const val MIN_SAVE_MS = 1_000L
    }
}
