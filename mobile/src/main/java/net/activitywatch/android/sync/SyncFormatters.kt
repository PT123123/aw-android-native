package net.activitywatch.android.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 展示辅助函数（与 Sync.vue 同名函数一致）
object SyncFormatters {

    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

    fun deviceTypeLabel(t: String?): String = when (t) {
        "windows" -> "Windows"
        "android" -> "Android"
        "ios" -> "iOS"
        "linux" -> "Linux"
        "macos" -> "macOS"
        else -> if (!t.isNullOrEmpty()) t else "-"
    }

    fun directionLabel(d: String): String = if (d == "out") "去向" else "来向"

    fun eventLabel(e: String?): String = when (e) {
        "discovery" -> "发现"
        "pairing" -> "配对"
        "sync" -> "同步"
        "conflict" -> "冲突"
        else -> if (!e.isNullOrEmpty()) e else "-"
    }

    fun protocolLabel(p: String?): String = when (p) {
        "http" -> "HTTP"
        "udp_broadcast" -> "UDP 广播"
        "mdns" -> "mDNS"
        else -> if (!p.isNullOrEmpty()) p else "-"
    }

    // 服务端时间戳为 UTC RFC3339；按本地时区展示。解析失败原样返回
    fun formatTime(ts: String?): String {
        if (ts.isNullOrBlank()) return "-"
        val millis = parseEpochMilli(ts) ?: return ts
        return fmt.format(Date(millis))
    }

    fun humanSize(n: Long?): String {
        if (n == null || n == 0L) return "-"
        if (n < 1024) return "$n B"
        return String.format(Locale.US, "%.1f KB", n / 1024.0)
    }

    fun isLoopback(ip: String?): Boolean {
        if (ip.isNullOrEmpty()) return true
        if (ip == "localhost" || ip == "0.0.0.0" || ip == "::1") return true
        return ip.startsWith("127.")
    }

    // 平均每 N 分钟 / N 小时 M 分钟 / N 天 H 小时（与 vue 的 getSyncFrequencyText 一致）
    fun syncFrequencyText(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return "尚未同步"
        if (minutes < 60) return "平均每 $minutes 分钟"
        val hours = minutes / 60
        val remainMins = minutes % 60
        if (hours < 24) return "平均每 $hours 小时 $remainMins 分钟"
        val days = hours / 24
        return "平均每 $days 天 ${hours % 24} 小时"
    }
}
