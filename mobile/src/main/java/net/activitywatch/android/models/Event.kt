package net.activitywatch.android.models

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant

data class Event(val timestamp: Instant, val duration: Double = 0.0, val data: JSONObject) {
    companion object {
        fun fromUsageEvent(usageEvent: UsageEvents.Event, context: Context, includeClassname: Boolean = true): Event {
            val timestamp = DateTimeUtils.toInstant(java.util.Date(usageEvent.timeStamp))
            val pm = context.packageManager
            // 解析失败（双开/工作资料、卸载残留、ROM 系统包等）时用包名兜底，
            // 不再写 "Unknown (包名)"；真实包名始终保留在 data.package 字段
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(usageEvent.packageName, PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES))
            } catch(e: PackageManager.NameNotFoundException) {
                usageEvent.packageName
            }

            // Construct the data object in an exception-safe manner
            val data = JSONObject()
            data.put("app", appName)
            data.put("package", usageEvent.packageName)
            if(includeClassname) {
                data.put("classname", usageEvent.className)
            }

            return Event(
                timestamp = timestamp,
                duration = 0.0,
                data = data
            )
        }
    }

    override fun toString(): String {
        val event = JSONObject()
        event.put("timestamp", timestamp)
        event.put("duration", duration)
        event.put("data", data)
        return event.toString()
    }
}