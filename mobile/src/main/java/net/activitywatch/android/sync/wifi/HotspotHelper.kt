package net.activitywatch.android.sync.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 「被传送方」的本地热点（Local-only Hotspot）封装：
 * 开启热点 → 取 SSID / 密码 → 解析本机在热点网络上的地址（同步服务可达地址）。
 *
 * 注意：
 * - 需要 ACCESS_FINE_LOCATION 运行时授权，且多数设备要求「位置服务」处于开启状态；
 * - 热点随应用退后台自动关闭，传输期间需保持应用在前台；
 * - [stop] 之后热点即刻关闭。
 */
class HotspotHelper(private val context: Context) {

    data class HotspotInfo(val ssid: String, val psk: String, val serverIp: String)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val isRunning: Boolean get() = reservation != null

    /**
     * 开启热点并解析信息。
     * [ipsBefore] 为调用方在开启前采集的本机 IPv4 集合（用于差分定位热点网关地址）。
     */
    @SuppressLint("MissingPermission") // 调用方已确保定位权限
    suspend fun start(ipsBefore: Set<String>, timeoutMs: Long = 30_000): HotspotInfo =
        withTimeout(timeoutMs) {
            val started = suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation> { cont ->
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                cont.invokeOnCancellation { stop() }
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        if (cont.isActive) cont.resume(res)
                    }

                    override fun onFailed(reason: Int) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(
                                    when (reason) {
                                        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                                            "无可用信道，无法开启热点（可尝试关闭 Wi-Fi 后重试）"
                                        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
                                            "开启热点失败（请确认已授予定位权限且位置服务已开启）"
                                        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                                            "设备处于不兼容模式（如省电 / 飞行模式），无法开启热点"
                                        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                                            "系统禁止开启热点（检查系统 / 厂商限制）"
                                        else -> "开启热点失败（原因码 $reason）"
                                    }
                                )
                            )
                        }
                    }

                    override fun onStopped() {
                        reservation = null
                    }
                }, Handler(Looper.getMainLooper()))
            }

            val (ssid, psk) = extractCredentials(started)
            if (ssid.isBlank() || psk.isBlank()) {
                stop()
                throw IllegalStateException("无法读取热点名称或密码")
            }
            val serverIp = resolveServerIp(ipsBefore)
            HotspotInfo(ssid, psk, serverIp)
        }

    private fun extractCredentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            (cfg.ssid ?: "") to (cfg.passphrase ?: "")
        } else {
            @Suppress("DEPRECATION")
            val cfg = res.wifiConfiguration
            (cfg?.SSID?.trim('"').orEmpty()) to (cfg?.preSharedKey?.trim('"').orEmpty())
        }
    }

    /**
     * 定位热点网关地址：开热点前后做 IPv4 集合差分，新出现的地址即热点网段的网关。
     * 差分失败时退回「常见 AP 网卡名」启发式。
     */
    private suspend fun resolveServerIp(ipsBefore: Set<String>): String =
        withContext(Dispatchers.IO) {
            repeat(20) {
                val fresh = currentIpv4s()
                val newOnes = fresh.filter { it !in ipsBefore && !it.startsWith("169.254.") }
                // 热点网关惯例在 192.168.x 段
                val preferred = newOnes.firstOrNull { it.startsWith("192.168.") }
                    ?: newOnes.firstOrNull()
                if (preferred != null) return@withContext preferred
                delay(300)
            }
            // 兜底：常见 AP 接口名
            val fallback = runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { it.isUp && !it.isLoopback }
                    .filter {
                        val n = it.name.lowercase()
                        n.contains("ap") || n.startsWith("swlan") || n == "wlan1"
                    }
                    .flatMap { ni -> ni.interfaceAddresses }
                    .mapNotNull { ia -> ia.address?.hostAddress }
                    .firstOrNull { !it.contains(':') && !it.startsWith("127.") && !it.startsWith("169.254.") }
            }.getOrNull()
            fallback ?: throw IllegalStateException("未能获取热点网关地址")
        }

    /** 关闭热点（幂等）。 */
    fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
    }

    companion object {
        /** 当前本机全部非回环 IPv4（排除链路本地 169.254.*）。 */
        fun currentIpv4s(): Set<String> = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.interfaceAddresses }
                .mapNotNull { ia -> ia.address?.hostAddress }
                .filter { !it.contains(':') && !it.startsWith("127.") && !it.startsWith("169.254.") }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
