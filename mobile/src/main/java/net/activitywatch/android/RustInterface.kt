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
        Log.d(TAG, "开始初始化 RustInterface")
        // NOTE: This doesn't work, probably because I can't get gradle to not strip symbols on release builds
        Os.setenv("RUST_BACKTRACE", "1", true)
        Log.d(TAG, "已设置 RUST_BACKTRACE 环境变量为 1")

        if(context != null) {
            Os.setenv("SQLITE_TMPDIR", context.cacheDir.absolutePath, true)
            Log.d(TAG, "已设置 SQLITE_TMPDIR 环境变量为 ${context.cacheDir.absolutePath}")
        }

        Log.d(TAG, "准备加载 aw_server 库")
        System.loadLibrary("aw_server")
        Log.d(TAG, "已加载 aw_server 库")

        Log.d(TAG, "调用 initialize 方法")
        //initialize()  // 对应到 Java_net_activitywatch_android_RustInterface_initialize
        Log.d(TAG, "initialize 方法调用完成") // TODO-照理说运行到了，但是没正常显示

        if(context != null) {
            Log.d(TAG, "准备设置数据目录为 ${context.filesDir.absolutePath}")
            setDataDir(context.filesDir.absolutePath) // 对应到Java_net_activitywatch_android_RustInterface_setDataDir，日志没正常显示
            Log.d(TAG, "已设置数据目录为 ${context.filesDir.absolutePath}")
        }
        Log.d(TAG, "RustInterface 初始化完成")
    }

    companion object {
        var serverStarted = false
    }

    private external fun initialize(): String
    private external fun greeting(pattern: String): String
    private external fun startServer()
    private external fun setDataDir(path: String)
    external fun getBuckets(): String
    external fun createBucket(bucket: String): String
    external fun getEvents(bucket_id: String, limit: Int): String
    external fun heartbeat(bucket_id: String, event: String, pulsetime: Double): String

    fun sayHello(to: String): String {
        Log.d(TAG, "调用 sayHello 方法，参数: $to")
        val result = greeting(to)
        Log.d(TAG, "sayHello 方法返回结果: $result")
        return result
    }

    fun startServerTask(context: Context) {
        Log.w(TAG, "调用Starting server...")
        if(!serverStarted) {
            Log.d(TAG, "服务器未启动，检查端口 5600 是否可用")
            // check if port 5600 is already in use
            try {
                val socket = java.net.ServerSocket(5600)
                socket.close()
                Log.d(TAG, "端口 5600 可用")
            } catch(e: java.net.BindException) {
                Log.e(TAG, "Port 5600 is already in use, server probably already started")
                return
            }

            Log.d(TAG, "标记服务器已启动")
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
