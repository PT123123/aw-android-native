package net.activitywatch.android.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 组播锁：安卓上 mDNS（224.0.0.251:5353）收发的前提。
 *
 * 为什么必须显式持有：Wi-Fi 芯片默认在省电状态下过滤掉组播帧，系统只在应用持有
 * [WifiManager.MulticastLock] 时才把组播投递上来（清单里的 CHANGE_WIFI_MULTICAST_STATE
 * 权限就是为此声明的）。没有它，Rust 侧的 mDNS 首选路径在手机上等于不存在，
 * 只剩 UDP 广播备选路径 —— 广播是**广播**帧，不走组播过滤，所以老方案从来不需要这把锁。
 *
 * 为什么不常驻：持锁会让芯片退出组播省电过滤，待机耗电明显上升，
 * 因此与 Rust 侧的发现开关同一生命周期：进局域网同步界面、后台重发现窗口期间才持有。
 *
 * 多持有方（界面 / 自愈窗口）按 owner 计数共享同一把锁，最后一个释放才真正放下。
 */
object MulticastLocks {

    private const val TAG = "MulticastLocks"
    private const val LOCK_TAG = "aw-sync-mdns"

    private var lock: WifiManager.MulticastLock? = null
    private val owners = mutableSetOf<String>()

    /** 为 [owner] 获取组播锁；同一 owner 重复调用只算一次，失败只记日志不影响同步。 */
    @Synchronized
    fun acquire(context: Context, owner: String) {
        if (owner in owners) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            Log.w(TAG, "无 WifiManager，组播锁不可用（mDNS 首选路径在本机不可用）")
            return
        }
        val l = lock ?: wm.createMulticastLock(LOCK_TAG).apply {
            // 计数自己做（owners）：系统那套引用计数要求每个 owner 严格配对，
            // 一旦页面异常重建就会失配，非计数模式下 release 一次即彻底放开。
            setReferenceCounted(false)
        }
        runCatching { l.acquire() }
            .onFailure { Log.w(TAG, "组播锁获取失败（$owner）：${it.message}"); return }
        lock = l
        owners.add(owner)
        Log.d(TAG, "组播锁已获取（$owner，持有方 ${owners.size} 个）")
    }

    @Synchronized
    fun release(owner: String) {
        if (!owners.remove(owner)) return
        if (owners.isNotEmpty()) {
            Log.d(TAG, "仍有 ${owners.size} 个持有方，暂不释放组播锁")
            return
        }
        runCatching { lock?.release() }.onFailure { Log.w(TAG, "组播锁释放异常：${it.message}") }
        Log.d(TAG, "组播锁已释放（$owner）")
    }
}
