package net.activitywatch.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.activitywatch.android.RustInterface

/**
 * 局域网同步的 Wi-Fi 自动开关：
 * 检测到 Wi-Fi 连接 → 自动开启局域网同步（enabled=true），并刷新注入 Rust 侧的 Wi-Fi IP；
 * 离开 Wi-Fi（流量/无网）→ 自动关闭（enabled=false）。无需人工开关。
 *
 * enabled 持久化在 Rust 侧 sync_config，由 aw-sync-rust 的 spawn_auto_sync（按 sync_interval
 * 周期双向同步）与 spawn_probe（在线探测）每轮读取后实际生效。
 *
 * 在进程生命周期注册一次（MainActivity.onCreate，紧随 Rust server 启动）。
 * 内嵌服务器冷启动需要几秒，配置写入失败时自动重试。
 */
object LanSyncNetworkMonitor {

    private const val TAG = "LanSyncNetworkMonitor"

    /** 网络抖动去抖：状态稳定该时长后才真正切换 */
    private const val DEBOUNCE_MS = 2000L

    /** 内嵌 Rust server 可能仍在启动，配置写入失败时重试 */
    private const val MAX_RETRY = 5
    private const val RETRY_DELAY_MS = 3000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registered = false
    private var lastApplied: Boolean? = null
    private var pendingWifi: Boolean? = null
    private var debounceJob: Job? = null

    /**
     * 当前 Wi-Fi 状态（含去抖中）：已生效或正在去抖确认均视为 Wi-Fi；
     * 首个网络回调到达前（状态未知）也放行——否则冷启动前 2 秒窗口内的
     * 首次笔记页拉取会被误跳过。代价仅为极少数场景下多一次有界超时的空尝试。
     */
    fun isWifiNow(): Boolean = synchronized(this) {
        lastApplied == true || pendingWifi == true ||
            (lastApplied == null && pendingWifi == null)
    }

    fun register(context: Context, rust: RustInterface) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "无法获取 ConnectivityManager，Wi-Fi 自动开关不可用")
            return
        }

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update(app, rust, caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }

            override fun onLost(network: Network) {
                // 默认网络丢失（Wi-Fi 断开且无其他网络）视为离开 Wi-Fi；
                // 若是 Wi-Fi→流量切换，紧随其后的默认网络 onCapabilitiesChanged 会再次确认为 false
                update(app, rust, false)
            }
        })
        Log.i(TAG, "已注册默认网络回调，Wi-Fi 自动开关生效")
    }

    private fun update(context: Context, rust: RustInterface, wifiNow: Boolean) {
        synchronized(this) {
            if (pendingWifi == wifiNow) return
            pendingWifi = wifiNow
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                synchronized(this@LanSyncNetworkMonitor) { pendingWifi = null }
                if (wifiNow == lastApplied) return@launch
                lastApplied = wifiNow
                apply(context, rust, wifiNow)
            }
        }
    }

    private suspend fun apply(context: Context, rust: RustInterface, wifi: Boolean) {
        Log.i(
            TAG,
            if (wifi) "检测到 Wi-Fi：自动开启局域网同步" else "离开 Wi-Fi：自动关闭局域网同步"
        )
        if (wifi) {
            // 刷新注入的 Wi-Fi 真实 IP（Rust 广播宣告与对端同步地址依赖它，重连后 IP 会变）
            rust.applySyncWifiIp(context)
        }
        val api = SyncApiClient.api
        for (attempt in 1..MAX_RETRY) {
            try {
                val cfg = api.getConfig()
                if (cfg.enabled != wifi) {
                    api.saveConfig(cfg.copy(enabled = wifi))
                    Log.i(TAG, "已${if (wifi) "开启" else "关闭"}局域网同步 (第 $attempt 次尝试)")
                } else {
                    Log.d(TAG, "enabled 已是 $wifi，无需变更")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "同步配置写入失败（${e.message}），稍后重试 $attempt/$MAX_RETRY")
                delay(RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "局域网同步自动${if (wifi) "开启" else "关闭"}失败：内嵌服务器持续不可达")
        // 复位以允许下一次网络事件重试
        synchronized(this) { lastApplied = null }
    }
}
