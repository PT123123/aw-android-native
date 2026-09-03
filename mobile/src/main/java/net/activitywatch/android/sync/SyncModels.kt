package net.activitywatch.android.sync

import com.google.gson.annotations.SerializedName
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime

// 数据模型：与 aw-sync-rust (aw-server-rust/aw-sync-rust) 的 JSON 序列化一一对应。
// JSON key 为 snake_case。

data class SyncConfig(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("http_enabled") val httpEnabled: Boolean = true,
    @SerializedName("discovery_method") val discoveryMethod: String = "broadcast",
    @SerializedName("listen_port") val listenPort: Int = 5600,
    @SerializedName("udp_port") val udpPort: Int = 46000,
    @SerializedName("sync_inbox") val syncInbox: Boolean = true,
    @SerializedName("sync_activity") val syncActivity: Boolean = true,
    @SerializedName("sync_todo") val syncTodo: Boolean = true,
    @SerializedName("self_alias") val selfAlias: String = "",
    @SerializedName("probe_interval") val probeInterval: Int = 10
)

data class Device(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("device_kind") val deviceKind: String = "unknown",
    @SerializedName("ip") val ip: String = "",
    @SerializedName("port") val port: Int = 0,
    @SerializedName("paired_at") val pairedAt: String? = null,
    @SerializedName("last_sync_at") val lastSyncAt: String? = null,
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
    @SerializedName("is_online") val isOnline: Boolean = false,
    @SerializedName("is_self") val isSelf: Boolean = false,
    @SerializedName("paired") val paired: Boolean = false,
    @SerializedName("alias") val alias: String? = null,
    // 仅 GET /devices 附带：是否有待本机确认的配对请求
    @SerializedName("incoming_pair_request") val incomingPairRequest: Boolean = false,
    // 仅 GET /info 附带：本机 IP 所在网卡名
    @SerializedName("ip_iface") val ipIface: String? = null
)

// 同步快照（WiFi 传输 / push 载荷）：与 aw-sync-rust SyncSnapshot 一一对应。
// activity / inbox / todo 是各目标库（sqlite.db / inbox.db / todo.db）导出的 JSON 文本。
data class SyncSnapshot(
    @SerializedName("source_device") val sourceDevice: Device? = null,
    @SerializedName("activity") val activity: String? = null,
    @SerializedName("inbox") val inbox: String? = null,
    @SerializedName("todo") val todo: String? = null
)

val Device.displayName: String
    get() = if (alias.isNullOrBlank()) name else alias

// 与 Sync.vue 一致：已配对设备信 is_online；未配对设备看 30s 内是否被发现过
val Device.isEffectivelyOnline: Boolean
    get() {
        if (paired) return isOnline
        val seen = parseEpochMilli(lastSeenAt) ?: return false
        return System.currentTimeMillis() - seen < 30_000L
    }

data class DiscoveryStatus(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("http_enabled") val httpEnabled: Boolean = false,
    @SerializedName("discovery_method") val discoveryMethod: String = "broadcast",
    @SerializedName("discovery_running") val discoveryRunning: Boolean = false,
    @SerializedName("udp_port") val udpPort: Int = 46000,
    @SerializedName("listen_port") val listenPort: Int = 5600,
    @SerializedName("self_device") val selfDevice: Device? = null
)

data class SyncLogEntry(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    // out / in
    @SerializedName("direction") val direction: String = "",
    // http / udp_broadcast / mdns
    @SerializedName("protocol") val protocol: String = "",
    @SerializedName("peer_id") val peerId: String? = null,
    // pairing / discovery / sync / conflict
    @SerializedName("event_type") val eventType: String = "",
    // success / failed / running
    @SerializedName("status") val status: String = "",
    @SerializedName("message") val message: String? = null,
    @SerializedName("data_size") val dataSize: Long? = null,
    // P1 起：逐条传输明细（可为 null 表示老数据无明细）
    @SerializedName("details") val details: List<SyncTransferRecord>? = null
)

/// 一条传输明细：某次同步中单条记录的操作结果。
data class SyncTransferRecord(
    @SerializedName("kind") val kind: String = "",
    @SerializedName("logical_key") val logicalKey: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("action") val action: String = "",
    @SerializedName("reason") val reason: String? = null
)

data class LogPage(
    @SerializedName("logs") val logs: List<SyncLogEntry> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

data class DeviceSyncStats(
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("pending_push_count") val pendingPushCount: Int = 0,
    @SerializedName("pending_conflict_count") val pendingConflictCount: Int = 0,
    @SerializedName("total_synced_count") val totalSyncedCount: Int = 0,
    @SerializedName("total_synced_size") val totalSyncedSize: Long = 0,
    @SerializedName("local_note_count") val localNoteCount: Int = 0,
    @SerializedName("remote_note_count") val remoteNoteCount: Int = 0,
    @SerializedName("last_sync_at") val lastSyncAt: String? = null,
    @SerializedName("last_full_sync_at") val lastFullSyncAt: String? = null,
    @SerializedName("sync_frequency_minutes") val syncFrequencyMinutes: Int? = null,
    @SerializedName("last_error") val lastError: String? = null,
    @SerializedName("last_error_at") val lastErrorAt: String? = null
)

data class ConflictSummary(
    @SerializedName("note_id") val noteId: Long = 0,
    @SerializedName("note_title") val noteTitle: String = "",
    @SerializedName("detected_at") val detectedAt: String = "",
    @SerializedName("resolved") val resolved: Boolean = false,
    @SerializedName("resolution") val resolution: String? = null
)

data class DebugEntry(
    @SerializedName("seq") val seq: Int = 0,
    @SerializedName("ts") val ts: String = "",
    @SerializedName("level") val level: String = "",
    @SerializedName("msg") val msg: String = ""
)

data class PairRequest(@SerializedName("device_id") val deviceId: String)

data class AliasRequest(@SerializedName("alias") val alias: String?)

data class SyncResult(
    @SerializedName("device_id") val deviceId: String = "",
    @SerializedName("applied") val applied: Int = 0
)

data class ConflictsResponse(@SerializedName("conflicts") val conflicts: List<ConflictSummary> = emptyList())

data class TrashEntry(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("kind") val kind: String = "",
    @SerializedName("logical_key") val logicalKey: String = "",
    @SerializedName("archived") val archived: String = "",
    @SerializedName("winner_rev") val winnerRev: String? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("source_device") val sourceDevice: String? = null,
    @SerializedName("archived_at") val archivedAt: String = "",
    @SerializedName("restored") val restored: Boolean = false
)

data class TrashResponse(
    @SerializedName("trash") val trash: List<TrashEntry> = emptyList(),
    @SerializedName("count") val count: Int = 0
)

// 回收站操作结果：restored / deleted / cleared / id
data class TrashOpResult(
    @SerializedName("restored") val restored: Boolean? = null,
    @SerializedName("deleted") val deleted: Boolean? = null,
    @SerializedName("cleared") val cleared: Int? = null,
    @SerializedName("id") val id: Long? = null
)

// 宽松承载各操作端点的返回（cleared / deleted / updated / saved / id 等）
data class OpResult(
    @SerializedName("cleared") val cleared: Int? = null,
    @SerializedName("deleted") val deleted: Boolean? = null,
    @SerializedName("updated") val updated: Boolean? = null,
    @SerializedName("saved") val saved: Boolean? = null,
    @SerializedName("id") val id: String? = null
)

fun parseEpochMilli(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    return try {
        Instant.parse(ts).toEpochMilli()
    } catch (e: Exception) {
        // 服务端 chrono 序列化带 +00:00 偏移时，Instant.parse 只接受 Z 后缀
        try {
            OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }
}
