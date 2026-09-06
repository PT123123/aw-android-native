package net.activitywatch.android.sync

import android.util.Log

/**
 * 局域网拉取：笔记页等本地页面刷新时顺带触发一次对全部已配对设备的双向同步。
 *
 * - 复用现有端点：GET /devices → 逐台 POST /devices/{id}/sync（拉-合-推）。
 * - 单台失败不影响其他设备；整链路 runCatching，绝不向调用方抛异常。
 * - 仅在 Wi-Fi 环境下执行（LanSyncNetworkMonitor 的去抖状态），流量环境直接跳过。
 */
object LanPull {

    private const val TAG = "LanPull"

    /** 对全部已配对设备执行一次双向同步，返回成功台数；任何异常都吞掉。 */
    suspend fun syncAllPairedNow(): Int {
        if (!LanSyncNetworkMonitor.isWifiNow()) return 0
        val api = SyncApiClient.api
        val devices = runCatching { api.getDevices() }.getOrNull() ?: return 0
        var ok = 0
        for (d in devices) {
            if (!d.paired || d.isSelf) continue
            runCatching { api.syncDevice(d.id) }
                .onSuccess { ok++ }
                .onFailure { e -> Log.d(TAG, "拉取 ${d.displayName} 失败: ${e.message}") }
        }
        return ok
    }
}
