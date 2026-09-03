package net.activitywatch.android.sync.wifi

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * WiFi 热点传输的二维码载荷。
 *
 * 被传送方（接收端）开启本地热点后，把 SSID / 密码 / 本机同步服务地址编码成二维码；
 * 传送方（发送端）扫码后按载荷自动连接热点，并直接通过 HTTP 与对端 [ip]:[port]
 * 上的 aw-sync 服务互传数据（复用局域网同步的 /snapshot /push /apply 端点）。
 */
data class QrPayload(
    /** 载荷格式版本 */
    val v: Int = VERSION,
    /** 类型标记，用于区分其它二维码 */
    val t: String = TYPE,
    /** 热点 SSID */
    val ssid: String,
    /** 热点密码（WPA2/WPA3 PSK） */
    val psk: String,
    /** 接收端在热点网络中的服务器地址（通常是网关，如 192.168.x.1） */
    val ip: String,
    /** 接收端同步服务端口（默认 5600） */
    val port: Int,
    /** 接收端设备 ID */
    val id: String,
    /** 接收端设备名（展示用） */
    val name: String
) {

    fun toJson(): String = gson.toJson(this)

    companion object {
        const val VERSION = 1
        const val TYPE = "aw-wifi-transfer"

        private val gson = Gson()

        fun fromJson(text: String): QrPayload? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                val p = gson.fromJson(trimmed, QrPayload::class.java)
                if (p.t != TYPE || p.v != VERSION || p.ssid.isBlank() || p.ip.isBlank() || p.port !in 1..65535) {
                    null
                } else {
                    p
                }
            } catch (e: JsonSyntaxException) {
                null
            }
        }
    }
}
