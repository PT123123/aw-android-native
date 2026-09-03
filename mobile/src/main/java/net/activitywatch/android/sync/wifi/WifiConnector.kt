package net.activitywatch.android.sync.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
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
 * 「传送方」连接对端热点：
 * - API 29+：WifiNetworkSpecifier + requestNetwork（系统弹窗确认后建立「仅本应用可见」
 *   的连接，不抢占系统默认网络，需持回调保持连接）；
 * - API 26~28：退回旧版 addNetwork / enableNetwork（该时代无限制，网络成为系统默认）。
 *
 * 返回的 [Network] 为 null 时表示走的是旧版路径（默认路由已切到热点，用裸 socket 即可）。
 */
object WifiConnector {

    class ConnectException(message: String) : Exception(message)

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 连接到二维码里的热点。[timeoutMs] 覆盖「等用户在系统弹窗里点确认」的时间。
     * WPA2 失败自动尝试一次 WPA3（部分机型本地热点默认 SAE）。
     */
    suspend fun connect(context: Context, payload: QrPayload, timeoutMs: Long = 120_000): Network? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return withTimeout(timeoutMs) {
                try {
                    requestSpecifier(context, payload, wpa3 = false)
                } catch (e: ConnectException) {
                    // 第二次机会：WPA3-SAE
                    try {
                        requestSpecifier(context, payload, wpa3 = true)
                    } catch (e2: Exception) {
                        throw e2
                    }
                }
            }
        }
        return connectLegacy(context, payload, timeoutMs)
    }

    private suspend fun requestSpecifier(
        context: Context,
        payload: QrPayload,
        wpa3: Boolean
    ): Network = suspendCancellableCoroutine { cont ->
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val builder = WifiNetworkSpecifier.Builder().setSsid(payload.ssid)
        if (wpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setWpa3Passphrase(payload.psk)
        } else {
            builder.setWpa2Passphrase(payload.psk)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        ConnectException(
                            if (wpa3) "连接热点失败（已尝试 WPA2 / WPA3，请确认密码或信号）"
                            else "连接热点未完成（可能被系统弹窗取消或被拒绝）"
                        )
                    )
                }
            }

            override fun onLost(network: Network) {
                // 已连接后又掉线：仅在等待期间视为失败，传输期的掉线由传输报错
                if (cont.isActive) {
                    cont.resumeWithException(ConnectException("热点连接已断开"))
                }
            }
        }
        activeCallback = cb
        cont.invokeOnCancellation {
            runCatching { cm.unregisterNetworkCallback(cb) }
            activeCallback = null
        }
        cm.requestNetwork(request, cb)
    }

    /** API 26~28 旧路径：addNetwork + enableNetwork（网络成为系统默认路由）。 */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun connectLegacy(
        context: Context,
        payload: QrPayload,
        timeoutMs: Long
    ): Network? = withContext(Dispatchers.IO) {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wc = WifiConfiguration().apply {
            SSID = "\"${payload.ssid}\""
            preSharedKey = "\"${payload.psk}\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val netId = wm.addNetwork(wc)
        if (netId == -1) throw ConnectException("添加热点配置失败")
        wm.enableNetwork(netId, true)

        withTimeout(timeoutMs) {
            while (true) {
                val info = wm.connectionInfo
                val cur = info?.ssid?.trim('"')
                if (cur == payload.ssid && info.ipAddress != 0) {
                    return@withTimeout null
                }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            null as Network?
        }
    }

    /** 传输结束后释放连接（specifier 网络会随回调注销而断开）。 */
    fun release(context: Context) {
        val cb = activeCallback ?: return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
        activeCallback = null
    }

    /** 当前是否有本应用持有到 Wi-Fi 的 specifier 网络（用于 UI 提示）。 */
    fun wifiInterfacesUp(): Boolean = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).any { it.isUp && it.name.startsWith("wlan") }
    }.getOrDefault(false)
}
