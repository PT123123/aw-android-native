package net.activitywatch.android.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Request

/**
 * 事件驱动的局域网同步（D）：本地数据一变就同步，不再只靠 Rust 侧的周期轮询。
 *
 * 本机的所有业务写入（笔记 / 待办 / 清单 / 评论 / 恢复版本 …）都走 127.0.0.1:5600 上
 * 以 `inbox` 开头的写接口，由 [LocalWriteWatcher] 拦截器在**写成功后**通知这里；连续写入
 * （编辑自动保存、批量操作）由单 worker 去抖聚合成一轮，避免一次编辑发几十次同步。
 *
 * 与同步自身的请求互不干扰：`sync` 端点下的请求与 GET 请求一律忽略，同步结果由 Rust
 * 直接写本地库（不经 HTTP），因此不存在「同步→触发→再同步」的回环。
 *
 * 真去同步的是 [LanPull.syncAllPairedNow]：非 Wi-Fi / 无配对设备时它会自己短路。
 */
object LocalChangeSync {

    private const val TAG = "LocalChangeSync"

    /** 去抖窗口：最后一次写入后安静该时长才同步（编辑自动保存会连续触发） */
    private const val DEBOUNCE_MS = 5_000L

    /** 两次同步之间的最小间隔：长时间连续编辑时不至于反复起同步 */
    private const val MIN_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 写入序号：每来一次本地写入 +1 */
    private var pendingSeq = 0L

    /** 已被同步覆盖到的序号 */
    private var syncedSeq = 0L

    /** 去抖 + 同步的单 worker（循环处理，天然合并连续写入）；退出时置空 */
    private var worker: Job? = null

    private var lastRunMs = 0L

    /** 本机写入成功 → 记一次变更；安静 [DEBOUNCE_MS] 后触发一轮双向同步 */
    fun onLocalWrite() {
        synchronized(this) {
            pendingSeq++
            if (worker?.isActive == true) return
            worker = scope.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        while (true) {
            // 1) 安静窗口：把连续写入合并成一次。
            //    退出判定与清空 worker 在同一把锁里完成——否则「worker 即将退出时来一次写入」
            //    会因为 isActive 仍为 true 而既不起新 worker、也不被本轮看到，导致变更被漏掉。
            delay(DEBOUNCE_MS)
            val target = synchronized(this) {
                if (pendingSeq == syncedSeq) {
                    worker = null
                    return
                }
                pendingSeq
            }
            // 2) 与上一轮同步保持最小间隔（不丢变更，只顺延）
            val wait = synchronized(this) {
                (lastRunMs + MIN_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            if (wait > 0) delay(wait)
            synchronized(this) { lastRunMs = System.currentTimeMillis() }
            // 3) 一轮双向同步（非 Wi-Fi / 无配对设备时返回 0）
            val synced = runCatching { LanPull.syncAllPairedNow() }.getOrDefault(0)
            synchronized(this) { if (target > syncedSeq) syncedSeq = target }
            if (synced > 0) {
                Log.i(TAG, "本地变更已同步到 $synced 台设备")
            } else {
                Log.d(TAG, "本地变更已记录，但当前无可用同步链路（非 Wi-Fi / 无配对设备）")
            }
        }
    }
}

/**
 * 本机业务写接口的监听拦截器：`POST/PUT/PATCH/DELETE` 且路径以 `/inbox/` 开头，
 * 响应成功（2xx）后通知 [LocalChangeSync] 触发去抖同步。
 *
 * 只认 `inbox` 前缀：同步自身用到的 `sync` 端点与查询接口（GET）不会被计入。
 * 挂到访问本机 5600 的 OkHttp 客户端上（LocalInboxApi / TodoApi）。
 */
object LocalWriteWatcher {

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful && isLocalDataWrite(request)) {
            LocalChangeSync.onLocalWrite()
        }
        response
    }

    private fun isLocalDataWrite(request: Request): Boolean = when (request.method) {
        "POST", "PUT", "PATCH", "DELETE" -> request.url.encodedPath.startsWith("/inbox/")
        else -> false
    }
}
