package net.activitywatch.android.queryexplorer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.activitywatch.android.dashboard.ActivityApi
import net.activitywatch.android.dashboard.QueryRequest
import net.activitywatch.android.dashboard.TimeRange
import net.activitywatch.android.dashboard.isoOf
import net.activitywatch.android.dashboard.rangeWindowMs
import retrofit2.HttpException

private const val TAG = "QueryViewModel"

data class QueryState(
    val loading: Boolean = false,
    val error: String? = null,
    val result: String? = null,
    val elapsedMs: Long = 0,
    val rowCount: Int? = null,
)

/** Query Explorer（对应 aw-webui 的 Query Explorer）：直接对 aw-server 跑 query2 脚本。 */
class QueryViewModel : ViewModel() {
    private val _state = MutableStateFlow(QueryState())
    val state: StateFlow<QueryState> = _state.asStateFlow()

    private val pretty = GsonBuilder().setPrettyPrinting().create()

    fun run(script: String, range: TimeRange) {
        // query2 按行解释，空行与前后空格没有意义，先清掉再发
        val lines = script.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            _state.value = QueryState(error = "查询脚本为空")
            return
        }

        val (start, end) = rangeWindowMs(range)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = QueryState(loading = true)
            val t0 = System.currentTimeMillis()
            try {
                ActivityApi.init()
                val req = QueryRequest(
                    timeperiods = listOf("${isoOf(start)}/${isoOf(end)}"),
                    query = lines,
                )
                val res = ActivityApi.service.query(req)
                val text = truncate(pretty.toJson(res))
                val rows = if (res.isJsonArray) res.asJsonArray.size() else null
                _state.value = QueryState(
                    result = text,
                    elapsedMs = System.currentTimeMillis() - t0,
                    rowCount = rows,
                )
            } catch (e: Exception) {
                Log.e(TAG, "query failed", e)
                _state.value = QueryState(
                    error = describe(e),
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> {
            // 语法错误时服务端把原因放在响应体里，只报 "HTTP 400" 没法排查
            val body = e.response()?.errorBody()?.string()?.trim()
            "HTTP ${e.code()}：${body?.take(600) ?: e.message()}"
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 结果可能几万字符，全部塞进 TextView 会卡住滚动，截断并标注原始长度。 */
    private fun truncate(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.take(MAX_RESULT_CHARS) + "\n…（结果过长已截断，共 ${text.length} 字符）"

    companion object {
        private const val MAX_RESULT_CHARS = 20_000
    }
}
