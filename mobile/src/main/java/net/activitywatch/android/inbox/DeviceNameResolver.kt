package net.activitywatch.android.inbox

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.activitywatch.android.db.DeviceIdProvider
import net.activitywatch.android.sync.SyncApiClient
import net.activitywatch.android.sync.Device
import net.activitywatch.android.sync.displayName

/**
 * 设备名解析工具：将 device_id 转成用户可读的显示名。
 *
 * 规则：
 * - 本机笔记 → "本设备（设备型号）"
 * - 同步过来的 → 设备名/别名（从本机服务器 GET api/0/sync/devices 解析）
 * - 解析不到 → "设备 + UUID 前 8 位"
 *
 * 进程内缓存，服务器不可用时静默回退短 ID，不阻塞 UI。
 */
object DeviceNameResolver {

    private val cache = mutableMapOf<String, String>()
    private var selfDeviceId: String? = null

    /**
     * 解析 device_id 为显示名。
     * @param context 用于获取本机 device_id
     * @param deviceId 要解析的设备 ID（可能为空）
     * @return 显示名
     */
    fun resolve(context: Context, deviceId: String?): String {
        if (deviceId.isNullOrEmpty()) return "未知设备"

        val selfId = selfDeviceId ?: DeviceIdProvider.getDeviceId(context.applicationContext).also {
            selfDeviceId = it
        }

        // 本机笔记
        if (deviceId == selfId) {
            val model = Build.MODEL.ifEmpty { "Android" }
            return "本设备（$model）"
        }

        // 缓存命中
        cache[deviceId]?.let { return it }

        // 回退短 ID
        return "设备 ${deviceId.take(8)}"
    }

    /**
     * 预解析一批 device_id，填充缓存。
     * 在列表滚动前批量调用，避免滚动时发请求。
     */
    suspend fun preload(context: Context, deviceIds: List<String>) {
        if (deviceIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val selfId = selfDeviceId ?: DeviceIdProvider.getDeviceId(context.applicationContext).also {
                    selfDeviceId = it
                }
                // 确保本机设备名已缓存
                if (selfId !in cache) {
                    val model = Build.MODEL.ifEmpty { "Android" }
                    cache[selfId] = "本设备（$model）"
                }
                // 从服务器获取设备列表
                val devices = SyncApiClient.api.getDevices()
                for (device in devices) {
                    if (device.id !in cache) {
                        cache[device.id] = device.displayName
                    }
                }
            } catch (_: Exception) {
                // 服务器不可用，静默忽略，resolve 会回退短 ID
            }
        }
    }

    /** 清除缓存（测试或设备列表变更时） */
    fun clear() {
        cache.clear()
        selfDeviceId = null
    }
}
