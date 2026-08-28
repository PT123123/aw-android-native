package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import android.widget.Toast
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "RustInterface"
class RustInterface constructor(context: Context? = null) {

    init {
        Log.d(TAG, "[初始化] 开始初始化 RustInterface")
        // NOTE: This doesn't work, probably because I can't get gradle to not strip symbols on release builds
        Os.setenv("RUST_BACKTRACE", "1", true)
        Log.d(TAG, "[环境变量] 已设置 RUST_BACKTRACE 环境变量为 1")

        if(context != null) {
            Os.setenv("SQLITE_TMPDIR", context.cacheDir.absolutePath, true)
            Log.d(TAG, "[环境变量] 已设置 SQLITE_TMPDIR 环境变量为 ${context.cacheDir.absolutePath}")
        }

        Log.d(TAG, "[库加载] 准备加载 aw_server 库")
        System.loadLibrary("aw_server")
        Log.d(TAG, "[库加载] 已加载 aw_server 库")

        Log.d(TAG, "[初始化] 调用 init法3")
        initialize()  // 对应到 Java_net_activitywatch_android_RustInterface_initialize
        Log.d(TAG, "[初始化] initialize 方法调用完成3")

        if(context != null) {
            Log.d(TAG, "[数据目录] 准备设置数据目录为 ${context.filesDir.absolutePath}")
            setDataDir(context.filesDir.absolutePath) // 对应到Java_net_activitywatch_android_RustInterface_setDataDir，日志没正常显示
            Log.d(TAG, "[数据目录] 已设置数据目录为 ${context.filesDir.absolutePath}")
            // 注入 Wi-Fi 链路真实 IP（绕过 VPN），供局域网同步展示/广播使用
            applySyncWifiIp(context)
        } else {
            Log.d(TAG, "[初始化] context为空")
        }
        Log.d(TAG, "[初始化] RustInterface 初始化完成")
    }

    companion object {
        var serverStarted = false
    }

    private external fun initialize()
    private external fun greeting(pattern: String): String
    private external fun startServer()
    private external fun setDataDir(path: String)
    private external fun setSyncLocalIp(ip: String)
    external fun getBuckets(): String
    external fun createBucket(bucket: String): String
    external fun getEvents(bucket_id: String, limit: Int): String
    external fun heartbeat(bucket_id: String, event: String, pulsetime: Double): String

    fun sayHello(to: String): String {
        Log.d(TAG, "[方法调用] 调用 sayHello 方法，参数: $to")
        val result = greeting(to)
        Log.d(TAG, "[方法调用] sayHello 方法返回结果: $result")
        return result
    }

    fun startServerTask(context: Context) {        Log.w(TAG, "[服务器] 调用Starting server...")
        if(!serverStarted) {
            Log.d(TAG, "[服务器] 服务器未启动，检查端口 5600 是否可用")
            // check if port 5600 is already in use
            try {
                val socket = java.net.ServerSocket(5600)
                socket.close()
                Log.d(TAG, "[服务器] 端口 5600 可用")
            } catch(e: java.net.BindException) {
                Log.e(TAG, "[服务器] Port 5600 is already in use, server probably already started")
                return
            }

            Log.d(TAG, "[服务器] 标记服务器已启动")
            serverStarted = true
            Log.d(TAG, "创建单线程执行器")
            val executor = Executors.newSingleThreadExecutor()
            Log.d(TAG, "创建主线程处理器")
            val handler = Handler(Looper.getMainLooper())
            Log.d(TAG, "提交任务到执行器")
            executor.execute {
                // will not block the UI thread
                Log.d(TAG, "任务开始执行")
                // Start server
                Log.w(TAG, "Starting server...")
                startServer()
                Log.d(TAG, "服务器已启动，通知主线程更新状态")

                handler.post {
                    // will run on UI thread after the task is done
                    Log.i(TAG, "Server finished")
                    Log.d(TAG, "标记服务器已停止")
                    serverStarted = false
                }
            }
            Log.w(TAG, "Server started")
        } else {
            Log.d(TAG, "服务器已启动，跳过启动流程")
        }
    }

    /**
     * 从 Wi-Fi 链路直接读取本机 IPv4（不受 VPN 影响），注入到 Rust 侧供局域网同步使用。
     * 优先用 ConnectivityManager 取 TRANSPORT_WIFI 网络的 LinkProperties（Android 10+ 开 VPN 时
     * WifiManager.getConnectionInfo() 经常返回 0，导致拿到错误网卡地址）；WifiManager 仅作兜底。
     * 读取失败时跳过，由 Rust 侧枚举网卡兜底。
     */
    fun applySyncWifiIp(context: Context) {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm == null) {
                Log.d(TAG, "[同步] 无法获取 ConnectivityManager，交由 Rust 枚举网卡兜底")
                return
            }
            var wifiIp: String? = null
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        wifiIp = addr.hostAddress
                        break
                    }
                }
                if (wifiIp != null) break
            }
            if (wifiIp != null) {
                Log.d(TAG, "[同步] 读取到 Wi-Fi 真实 IP: $wifiIp")
                setSyncLocalIp(wifiIp)
                return
            }
            // 兜底：旧式 WifiManager
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ipStr = android.text.format.Formatter.formatIpAddress(ipInt)
                Log.d(TAG, "[同步] 经 WifiManager 兜底读取到 Wi-Fi IP: $ipStr")
                setSyncLocalIp(ipStr)
            } else {
                Log.d(TAG, "[同步] 未获取到 Wi-Fi IP，交由 Rust 枚举网卡兜底")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[同步] 读取 Wi-Fi IP 失败: ${e.message}")
        }
    }

    fun createBucketHelper(bucket_id: String, type: String, hostname: String = "unknown", client: String = "aw-android") {
        Log.d(TAG, "调用 createBucketHelper 方法，参数: bucket_id=$bucket_id, type=$type, hostname=$hostname, client=$client")
        if(bucket_id in getBucketsJSON().keys().asSequence()) {
            Log.i(TAG, "Bucket with ID '$bucket_id', already existed. Not creating.")
        } else {
            val bucketJson = """{"id": "$bucket_id", "type": "$type", "hostname": "$hostname", "client": "$client"}"""
            Log.d(TAG, "准备创建新桶，JSON 数据: $bucketJson")
            val msg = createBucket(bucketJson);
            Log.w(TAG, msg)
        }
    }

    fun heartbeatHelper(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double = 60.0) {
        Log.d(TAG, "调用 heartbeatHelper 方法，参数: bucket_id=$bucket_id, timestamp=$timestamp, duration=$duration, pulsetime=$pulsetime")
        val event = Event(timestamp, duration, data)
        Log.d(TAG, "生成的事件数据: ${event.toString()}")
        val msg = heartbeat(bucket_id, event.toString(), pulsetime)
        Log.w(TAG, "heartbeat 方法返回消息: $msg")
    }

    fun getBucketsJSON(): JSONObject {
        Log.d(TAG, "调用 getBucketsJSON 方法")
        // TODO: Handle errors
        val json = JSONObject(getBuckets())
        if(json.length() <= 0) {
            Log.w(TAG, "Length: ${json.length()}")
        }
        Log.d(TAG, "getBucketsJSON 方法返回结果: $json")
        return json
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        Log.d(TAG, "调用 getEventsJSON 方法，参数: bucket_id=$bucket_id, limit=$limit")
        // TODO: Handle errors
        val result = getEvents(bucket_id, limit)
        Log.d(TAG, "getEvents 方法返回结果: $result")
        return try {
            val jsonArray = JSONArray(result)
            Log.d(TAG, "成功解析为 JSONArray: $jsonArray")
            jsonArray
        } catch(e: JSONException) {
            Log.e(TAG, "Error when trying to fetch events from bucket: $result")
            Log.d(TAG, "返回空的 JSONArray")
            JSONArray()
        }
    }

    fun test() {
        // TODO: Move to instrumented test
        Log.w(TAG, sayHello("Android"))
        createBucketHelper("test", "test")
        Log.w(TAG, getBucketsJSON().toString(2))

        val event = """{"timestamp": "${Instant.now()}", "duration": 0, "data": {"key": "value"}}"""
        Log.w(TAG, event)
        Log.w(TAG, heartbeat("test", event, 60.0))
        Log.w(TAG, getBucketsJSON().toString(2))
        Log.w(TAG, getEventsJSON("test").toString(2))
    }
}
