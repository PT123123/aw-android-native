package net.activitywatch.android.watcher

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat

const val bucket_id = "aw-watcher-android-test"
const val unlock_bucket_id = "aw-watcher-android-unlock"

class UsageStatsWatcher constructor(val context: Context) {
    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    var lastUpdated: Instant? = null


    enum class PermissionStatus {
        GRANTED, DENIED, CANNOT_BE_GRANTED
    }

    companion object {
        const val TAG = "UsageStatsWatcher"

        fun isUsageAllowed(context: Context): Boolean {
            Log.d(TAG, "测试调用1isUsageAllowed")
            // https://stackoverflow.com/questions/27215013/check-if-my-application-has-usage-access-enabled
            val applicationInfo: ApplicationInfo = try {
                context.packageManager.getApplicationInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, e.toString())
                return false
            }
            Log.d(TAG, "测试2调用isUsageAllowed")
            return getUsageStatsPermissionsStatus(context)
        }

        fun isAccessibilityAllowed(context: Context): Boolean {
            return getAccessibilityPermissionStatus(context)
        }

        private fun getUsageStatsPermissionsStatus(context: Context): Boolean {
            Log.d(TAG, "测试1getUsageStatsPermissionsStatus")
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            Log.d(TAG, "测试2getUsageStatsPermissionsStatus2")
           return if (mode == AppOpsManager.MODE_DEFAULT) context.checkCallingOrSelfPermission(
                    Manifest.permission.PACKAGE_USAGE_STATS
                ) == PackageManager.PERMISSION_GRANTED else mode == AppOpsManager.MODE_ALLOWED
        }

        private fun getAccessibilityPermissionStatus(context: Context): Boolean {
            Log.d(TAG, "测试getAccessibilityPermissionStatus")
            // https://stackoverflow.com/a/54839499/4957939
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val accessibilityServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityEvent.TYPES_ALL_MASK)
            return accessibilityServices.any { it.id.contains(context.packageName) }
        }
    }

    private fun getUSM(): UsageStatsManager? {
        Log.d(TAG, "测试getUSM")
        Log.d(TAG, "开始检查 UsageStats 权限")
        val usageIsAllowed = isUsageAllowed(context)
        Log.d(TAG, "UsageStats 权限检查结果: $usageIsAllowed")

        return if (usageIsAllowed) {
            Log.d(TAG, "已获得 UsageStats 权限，开始获取 UsageStatsManager 实例")
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            Log.d(TAG, "成功获取 UsageStatsManager 实例")
            usm
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")

            // Unused, deprecated in favor of OnboardingActivity
            /*
            Handler(Looper.getMainLooper()).post {
                // Create an alert dialog to inform the user
                AlertDialog.Builder(context)
                    .setTitle("ActivityWatch needs Usage Access")
                    .setMessage("This gives ActivityWatch access to your device use data, which is required for the basic functions of the application.\n\nWe respect your privacy, no data leaves your device.")
                    .setPositiveButton("Continue") { _, _ ->
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.cancel()
                        System.exit(0)
                    }
                    .show()
            }
             */
            Log.d(TAG, "因无权限，返回 null")
            null
        }
    }

    private var alarmMgr: AlarmManager? = null
    private lateinit var alarmIntent: PendingIntent

    fun setupAlarm() {
        Log.d(TAG, "开始设置定时任务")
        alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Log.d(TAG, "已获取 AlarmManager 实例")
        alarmIntent = Intent(context, AlarmReceiver::class.java).let { intent ->
            intent.action = "net.activitywatch.android.watcher.LOG_DATA"
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }
        Log.d(TAG, "已创建 PendingIntent")

        val interval = AlarmManager.INTERVAL_HOUR   // Or if testing: AlarmManager.INTERVAL_HOUR / 60
        alarmMgr?.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval,
            interval,
            alarmIntent
        )
        Log.d(TAG, "定时任务设置完成")
    }


    fun queryUsage() {
        Log.d(TAG, "开始查询 UsageStats")
        val usm = getUSM() ?: return
        Log.d(TAG, "已获取 UsageStatsManager 实例，开始查询每日使用统计")

        // Print per application
        val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
        Log.i(TAG, "usageStats.size=${usageStats.size}")
        for(e in usageStats) {
            Log.i(TAG, "${e.packageName}: ${e.totalTimeInForeground/1000}")
        }
        Log.d(TAG, "UsageStats 查询完成")
    }

    private fun getLastEvent(): JSONObject? {
        Log.d(TAG, "开始获取最后一个事件")
        val events = ri.getEventsJSON(bucket_id, limit=1)
        Log.d(TAG, "已获取事件列表，长度: ${events.length()}")
        return if (events.length() == 1) {
            //Log.d(TAG, "Last event: ${events[0]}")
            Log.d(TAG, "成功获取最后一个事件")
            events[0] as JSONObject
        } else {
            Log.w(TAG, "More or less than one event was retrieved when trying to get last event, actual length: ${events.length()}")
            Log.d(TAG, "因事件数量不符合要求，返回 null")
            null
        }
    }

    // TODO: Maybe return end of event instead of start?
    private fun getLastEventTime(): Instant? {
        Log.d(TAG, "开始获取最后一个事件的时间")
        val lastEvent = getLastEvent()
        Log.w(TAG, "Last event: $lastEvent")

        return if(lastEvent != null) {
            Log.d(TAG, "已获取最后一个事件，开始解析时间戳")
            val timestampString = lastEvent.getString("timestamp")
            // Instant.parse("2014-10-23T00:35:14.800Z").toEpochMilli()
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                val instant = DateTimeUtils.toInstant(timeCreatedDate)
                Log.d(TAG, "时间戳解析成功")
                instant
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse timestamp: $timestampString")
                Log.d(TAG, "因时间戳解析失败，返回 null")
                null
            }
        } else {
            Log.d(TAG, "因未获取到最后一个事件，返回 null")
            null
        }
    }

    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Sending heartbeats...")
            Log.d(TAG, "开始创建数据桶")
            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            ri.createBucketHelper(unlock_bucket_id, "os.lockscreen.unlocks")
            Log.d(TAG, "数据桶创建完成")
            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

            Log.d(TAG, "开始获取 UsageStatsManager 实例")
            val usm = getUSM() ?: return 0
            Log.d(TAG, "已获取 UsageStatsManager 实例")

            // Store activities here that have had a RESUMED but not a PAUSED event.
            // (to handle out-of-order events)
            //val activeActivities = [];

            // TODO: Fix issues that occur when usage stats events are out of order (RESUME before PAUSED)
            var heartbeatsSent = 0
            Log.d(TAG, "开始查询使用事件")
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)
            Log.d(TAG, "使用事件查询完成，开始遍历事件")
            nextEvent@ while(usageEvents.hasNextEvent()) {
                Log.d(TAG, "获取下一个使用事件")
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                // Log screen unlock
                if(event.eventType !in arrayListOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED)) {
                    if(event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN){
                        Log.d(TAG, "检测到屏幕解锁事件，开始发送心跳")
                        val timestamp = DateTimeUtils.toInstant(java.util.Date(event.timeStamp))
                        // NOTE: getLastEventTime() returns the last time of an event from  the activity bucket(bucket_id)
                        // Therefore, if an unlock happens after last event from main bucket, unlock event will get sent twice.
                        // Fortunately not an issue because identical events will get merged together (see heartbeats)
                        ri.heartbeatHelper(unlock_bucket_id, timestamp, 0.0, JSONObject(), 0.0)
                        Log.d(TAG, "屏幕解锁事件心跳发送完成")
                    }
                    // Not sure which events are triggered here, so we use a (probably safe) fallback
                    //Log.d(TAG, "Rare eventType: ${event.eventType}, skipping")
                    Log.d(TAG, "检测到罕见事件类型: ${event.eventType}，跳过该事件")
                    continue@nextEvent
                }

                // Log activity
                Log.d(TAG, "检测到活动事件，开始创建 Event 实例")
                val awEvent = Event.fromUsageEvent(event, context, includeClassname = true)
                Log.d(TAG, "Event 实例创建完成，开始确定脉冲时间")
                val pulsetime: Double
                when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // ACTIVITY_RESUMED: Activity was opened/reopened
                        pulsetime = 1.0
                        Log.d(TAG, "活动恢复事件，脉冲时间设置为 1.0")
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // ACTIVITY_PAUSED: Activity was moved to background
                        pulsetime = 24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                        Log.d(TAG, "活动暂停事件，脉冲时间设置为 24 小时")
                    }
                    else -> {
                        Log.w(TAG, "This should never happen!")
                        Log.d(TAG, "遇到意外事件类型，跳过该事件")
                        continue@nextEvent
                    }
                }

                Log.d(TAG, "开始发送活动事件心跳")
                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                Log.d(TAG, "活动事件心跳发送完成")
                if(heartbeatsSent % 100 == 0) {
                    Log.d(TAG, "心跳发送数量达到 100 的倍数，发布进度更新")
                    publishProgress(awEvent.timestamp)
                }
                heartbeatsSent++
            }
            Log.d(TAG, "使用事件遍历完成，返回发送的心跳数量")
            return heartbeatsSent
        }

        override fun onProgressUpdate(vararg progress: Instant) {
            lastUpdated = progress[0]
            Log.i(TAG, "Progress: ${lastUpdated.toString()}")
            Log.d(TAG, "进度更新完成")
            // The below is useful in testing, but otherwise just noisy.
            //Toast.makeText(context, "Logging data, progress: $lastUpdated", Toast.LENGTH_LONG).show()
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendHeartbeatTask, sent $result events")
            Log.d(TAG, "SendHeartbeatTask 执行完成")
            // The below is useful in testing, but otherwise just noisy.
            /*
            if(result != 0) {
                Toast.makeText(context, "Completed logging of data! Logged events: $result", Toast.LENGTH_LONG).show()
            }
            */
        }
    }

    /***
     * Returns the number of events sent
     */
    fun sendHeartbeats() {
        Log.w(TAG, "Starting SendHeartbeatTask")
        Log.d(TAG, "开始执行 SendHeartbeatTask")
        SendHeartbeatsTask().execute()
    }
}
