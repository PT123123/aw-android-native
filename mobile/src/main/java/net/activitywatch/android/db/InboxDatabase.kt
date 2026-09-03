package net.activitywatch.android.db

import android.content.Context
import java.util.UUID

/**
 * 设备身份：X-Device-ID 请求头来源（局域网同步 / inbox / todo API 共用）。
 * 注：原 Room InboxDatabase（本地优先同步的整套 SyncConflict/NoteSyncMap/vector_clock 设计）
 * 已于 P3 清理移除，此处仅保留设备标识；笔记数据统一走本机 aw-server-rust 的 inbox API。
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceName = prefs.getString(KEY_DEVICE_NAME, null)
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = android.os.Build.MODEL
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply()
        }
        return deviceName!!
    }

    fun setDeviceName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}
