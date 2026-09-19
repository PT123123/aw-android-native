package net.activitywatch.android.sync

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 应用级「远端数据已落地」事件总线。
 *
 * 发射方：
 *  - [LocalChangeSync]：一轮双向同步成功（synced > 0）后；
 *  - [LanSyncNetworkMonitor]：网络自愈路径的同步成功后；
 *  - [pollRevisionLoop]：轮询 GET /api/0/sync/revision 发现修订号变化时
 *    （内嵌 Rust server 在远端改动落库时递增修订号，见 aw-sync-rust manager.rs）。
 *
 * 订阅方（收件箱页 / 任务页，视图存活期订阅）：收到事件后重拉本页列表，
 * 让对端同步过来的改动即刷即现，不必等用户手动下拉。
 */
object RemoteSyncBus {

    private const val TAG = "RemoteSyncBus"

    /** 修订号轮询间隔（与桌面端同档） */
    private const val REVISION_POLL_MS = 15_000L

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 远端数据已落地（订阅方重拉列表；无订阅时静默丢弃） */
    val events = _events.asSharedFlow()

    fun emitRemoteDataLanded() {
        _events.tryEmit(Unit)
    }

    /**
     * 修订号轮询（挂起、不返回）：每 [intervalMs] 查一次本机 server 的数据修订号，
     * 值变化即发一条「远端数据已落地」。server 未就绪/失败时静默跳过该轮。
     * 由页面在 repeatOnLifecycle(STARTED) 里启动，离开页面协程取消、轮询停止。
     */
    suspend fun pollRevisionLoop(intervalMs: Long = REVISION_POLL_MS) {
        var last = -1L
        while (true) {
            val rev = runCatching { SyncApiClient.api.getRevision().revision }
                .onFailure { Log.d(TAG, "查询修订号失败：${it.message}") }
                .getOrDefault(-1L)
            if (rev >= 0) {
                if (last >= 0 && rev != last) {
                    Log.i(TAG, "数据修订号 $last → $rev，通知页面刷新")
                    emitRemoteDataLanded()
                }
                last = rev
            }
            delay(intervalMs)
        }
    }
}
