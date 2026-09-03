package net.activitywatch.android.sync.wifi

import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 极简 HTTP/1.1 客户端，专用于「WiFi 热点传输」访问对端：
 *
 * 1. WifiNetworkSpecifier 申请到的网络不会成为系统默认路由，必须用
 *    [Network.socketFactory] 显式建连，否则流量会走移动数据/原 Wi-Fi 而到不了对端；
 *    进程内 Rust 服务的自发请求同理到不了，因此传输由 Kotlin 侧中转
 *    （拉对端 /snapshot 回本机 /apply，再导出本机 /snapshot 推给对端 /push）。
 * 2. 走原生 socket 绕开了 network_security_config 的明文域名白名单——
 *    对端热点网关 IP 每次都可能不同，无法穷举进白名单；平台策略只约束
 *    OkHttp / HttpURLConnection 等库层，不拦裸 socket。
 *
 * 本机（127.0.0.1）调用不受影响，仍走 SyncApiClient 的 Retrofit。
 */
object WifiHttp {

    class HttpException(message: String) : Exception(message)

    fun get(
        network: Network?,
        ip: String,
        port: Int,
        path: String,
        readTimeoutMs: Int = 90_000
    ): String = request(network, ip, port, "GET", path, null, readTimeoutMs)

    fun postJson(
        network: Network?,
        ip: String,
        port: Int,
        path: String,
        body: String,
        readTimeoutMs: Int = 300_000
    ): String = request(network, ip, port, "POST", path, body, readTimeoutMs)

    private fun request(
        network: Network?,
        ip: String,
        port: Int,
        method: String,
        path: String,
        body: String?,
        readTimeoutMs: Int
    ): String {
        val socket = if (network != null) network.socketFactory.createSocket() else Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs

            val out = BufferedOutputStream(socket.getOutputStream())
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $ip:$port\r\n")
                append("Connection: close\r\n")
                append("Accept: application/json\r\n")
                append("User-Agent: aw-android-wifi-transfer\r\n")
                if (bodyBytes != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                }
                append("\r\n")
            }
            out.write(head.toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())

            // ---- 状态行 ----
            val statusLine = readLine(input)
                ?: throw HttpException("对端无响应（连接被关闭）")
            if (!statusLine.startsWith("HTTP/")) throw HttpException("非 HTTP 响应: $statusLine")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw HttpException("无法解析状态行: $statusLine")

            // ---- 响应头 ----
            var contentLength: Long = -1
            var chunked = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "content-length") contentLength = value.toLongOrNull() ?: -1
                if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
            }

            // ---- 响应体 ----
            val bytes = if (chunked) readChunked(input) else readFully(input, contentLength)
            val text = String(bytes, StandardCharsets.UTF_8)
            if (statusCode !in 200..299) {
                throw HttpException("HTTP $statusCode：${text.take(300)}")
            }
            return text
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 读一行（CRLF / LF 均可），流结束返回 null；空行返回 "" */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString("UTF-8").trimEnd('\r', '\n')
            }
            if (b == '\n'.code) {
                val line = buf.toString("UTF-8")
                return line.trimEnd('\r', '\n')
            }
            buf.write(b)
        }
    }

    private fun readFully(input: InputStream, contentLength: Long): ByteArray {
        val out = ByteArrayOutputStream(CHUNK_BUF)
        val buf = ByteArray(CHUNK_BUF)
        var remaining = if (contentLength >= 0) contentLength else -1L
        while (remaining != 0L) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (remaining > 0) {
                remaining -= n
                if (remaining < 0) remaining = 0
            }
        }
        return out.toByteArray()
    }

    /** Transfer-Encoding: chunked 解码 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(CHUNK_BUF)
        while (true) {
            val sizeLine = readLine(input)
                ?: throw HttpException("chunked 响应意外中断")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw HttpException("无法解析 chunk 大小: $sizeLine")
            if (size == 0) break
            var left = size
            val buf = ByteArray(CHUNK_BUF)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(left, buf.size))
                if (n < 0) throw HttpException("chunked 响应意外中断")
                out.write(buf, 0, n)
                left -= n
            }
            // chunk 数据后的 CRLF
            readLine(input)
        }
        return out.toByteArray()
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val CHUNK_BUF = 64 * 1024
}
