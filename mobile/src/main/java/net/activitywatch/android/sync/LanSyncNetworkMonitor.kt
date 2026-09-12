package net.activitywatch.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
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
import java.net.Inet4Address

/**
 * 局域网同步的 Wi-Fi 自动开关 + 网络自愈：
 * 检测到 Wi-Fi 连接 → 自动开启局域网同步（enabled=true），并刷新注入 Rust 侧的 Wi-Fi IP；
 * 离开 Wi-Fi（流量/无网）→ 自动关闭（enabled=false）。无需人工开关。
 *
 * enabled 持久化在 Rust 侧 sync_config，由 aw-sync-rust 的 spawn_auto_sync（按 sync_interval
 * 周期双向同步）与 spawn_probe（在线探测）每轮读取后实际生效。
 *
 * 【网络自愈】设备记录里的对端地址只在「进局域网同步界面」时才会被 UDP 广播刷新，后台期间
 * 对端换了 IP（DHCP 重分配）就会让自动同步永远按旧地址探测失败、再也不会重试。这里在两类
 * 网络事件后主动开一个短暂的后台重发现窗口（Rust 侧 discovery/burst）并立刻同步一轮，
 * 让 IP 变化自愈，不必手动进页面点「同步」：
 *   ① 回到 Wi-Fi（enabled 由关到开）；
 *   ② 同一 Wi-Fi 下本机 IP 变化（DHCP 重分配 / 漫游）。
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

    /** 后台重发现窗口长度（秒）与等窗口内广播刷新完成的等待时长（毫秒） */
    private const val BURST_SECS = 5
    private const val BURST_WAIT_MS = 4000L

    /** 自愈的最小触发间隔：网络抖动期不反复重发现 */
    private const val HEAL_MIN_INTERVAL_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registered = false
    private var lastApplied: Boolean? = null
    private var pendingWifi: Boolean? = null
    private var debounceJob: Job? = null

    /** 最近一次观察到的 Wi-Fi IPv4：用于识别「同一 Wi-Fi 下 IP 变了」 */
    private var lastWifiIp: String? = null
    private var lastHealMs = 0L

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

            /** 同一 Wi-Fi 下 IP 变化（DHCP 重分配/漫游）：不影响 enabled，但对端记录会失准 */
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                val ip = firstIpv4(lp) ?: return
                val changed = synchronized(this@LanSyncNetworkMonitor) {
                    if (ip == lastWifiIp) {
                        false
                    } else {
                        lastWifiIp = ip
                        true
                    }
                }
                if (!changed) return
                Log.i(TAG, "Wi-Fi IP 变为 $ip：刷新注入 IP 并触发一次重发现同步")
                scope.launch {
                    rust.applySyncWifiIp(app)
                    rediscoverAndSyncNow()
                }
            }
        })
        Log.i(TAG, "已注册默认网络回调，Wi-Fi 自动开关 + 网络自愈生效")
    }

    /** LinkProperties 里的第一个非回环 IPv4（无则 null） */
    private fun firstIpv4(lp: LinkProperties): String? {
        for (la in lp.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
        }
        return null
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
                if (wifi) {
                    // 回到 Wi-Fi：先开重发现窗口刷新双方记录里的对端 IP，再同步一轮；
                    // 不等 auto_sync 的轮询周期——对端 IP 变了的话它只会一直探测失败。
                    rediscoverAndSyncNow()
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

    /**
     * 开一个短暂的后台重发现窗口，等对端广播把设备记录里的 IP 刷新成当前真实地址，
     * 再对全部已配对设备跑一轮双向同步。全程静默：失败只记日志，不打扰用户。
     * 触发频率受 [HEAL_MIN_INTERVAL_MS] 限制。
     */
    private suspend fun rediscoverAndSyncNow() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastHealMs < HEAL_MIN_INTERVAL_MS) return
            lastHealMs = now
        }
        runCatching { SyncApiClient.api.startDiscoveryBurst(BURST_SECS) }
            .onFailure { Log.w(TAG, "开启后台重发现窗口失败：${it.message}") }
        delay(BURST_WAIT_MS)
        val synced = runCatching { LanPull.syncAllPairedNow() }.getOrDefault(0)
        Log.i(TAG, "重发现窗口结束，本轮双向同步成功 $synced 台设备")
    }
}
