package net.activitywatch.android.sync

import com.google.gson.annotations.SerializedName
import java.util.*

// ==================== 请求/响应模型 ====================

data class SyncRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("base_version") val baseVersion: Long,
    @SerializedName("device_versions") val deviceVersions: Map<String, Long>,
    @SerializedName("last_full_sync_at") val lastFullSyncAt: Long?,
    @SerializedName("push_changes") val pushChanges: List<PushChange>,
    @SerializedName("pull_limit") val pullLimit: Int = 500
)

data class PushChange(
    @SerializedName("type") val type: ChangeType,
    @SerializedName("note_id") val noteId: Long? = null,
    @SerializedName("local_version") val localVersion: Long? = null,
    @SerializedName("expected_version") val expectedVersion: Long? = null,
    @SerializedName("note") val note: InboxNote? = null,
    @SerializedName("fields") val fields: Map<String, Any>? = null
)

enum class ChangeType { CREATE, UPDATE, DELETE }

data class SyncResponse(
    @SerializedName("current_version") val currentVersion: Long,
    @SerializedName("pulled_notes") val pulledNotes: List<InboxNote>,
    @SerializedName("has_more") val hasMore: Boolean,
    @SerializedName("conflicts") val conflicts: List<SyncConflict>,
    @SerializedName("push_results") val pushResults: List<PushResult>,
    @SerializedName("device_states") val deviceStates: Map<String, DeviceState>
)

data class SyncConflict(
    @SerializedName("note_id") val noteId: Long,
    @SerializedName("server_version") val serverVersion: Long,
    @SerializedName("client_expected_version") val clientExpectedVersion: Long,
    @SerializedName("server_note") val serverNote: InboxNote,
    @SerializedName("client_note") val clientNote: InboxNote,
    @SerializedName("common_ancestor_version") val commonAncestorVersion: Long
)

data class PushResult(
    @SerializedName("local_version") val localVersion: Long? = null,
    @SerializedName("note_id") val noteId: Long? = null,
    @SerializedName("server_version") val serverVersion: Long? = null,
    @SerializedName("status") val status: PushStatus
)

enum class PushStatus { CREATED, UPDATED, DELETED, CONFLICT, ERROR }

data class DeviceState(
    @SerializedName("version") val version: Long,
    @SerializedName("last_seen") val lastSeen: String,
    @SerializedName("pending") val pending: Int
)

data class DeviceListResponse(
    @SerializedName("devices") val devices: List<SyncDevice>,
    @SerializedName("global_version") val globalVersion: Long
)

data class DeviceHeartbeat(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("platform") val platform: String,
    @SerializedName("pending_changes") val pendingChanges: Int,
    @SerializedName("local_version") val localVersion: Long
)

// ==================== 本地数据模型 ====================

data class InboxNote(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: String,  // ISO8601
    @SerializedName("updated_at") val updatedAt: String,  // ISO8601
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("version") val version: Long = 0,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("deleted") val deleted: Boolean = false,
    @SerializedName("synced_at") val syncedAt: Long? = null,
    @SerializedName("conflict") val conflict: Boolean = false
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "content" to content,
            "tags" to tags
        )
    }
}

data class LocalNote(
    val id: Long = 0,
    val serverId: Long? = null,
    val content: String,
    val timestamp: Long,
    val updatedAt: Long,
    val tags: List<String> = emptyList(),
    val version: Long = 0,
    val deviceId: String,
    val deleted: Boolean = false,
    val syncedAt: Long? = null,
    val pendingSync: Boolean = true,
    val localVersion: Long = 0
)

// ==================== 同步状态模型 ====================

data class SyncState(
    val deviceId: String,
    val serverVersion: Long = 0,
    val vectorClock: Map<String, Long> = emptyMap(),
    val lastFullSyncAt: Long? = null,
    val lastSyncAt: Long? = null,
    val pendingPushCount: Int = 0,
    val pendingConflictCount: Int = 0,
    val lastError: String? = null
)

data class SyncDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val lastSeenAt: Long,
    val lastSyncedAt: Long?,
    val pendingChanges: Int,
    val version: Long,
    val isCurrent: Boolean,
    val status: DeviceStatus
)

enum class DeviceStatus { ONLINE, OFFLINE, SYNCING, ERROR, CONFLICT }

// ==================== 同步进度/日志 ====================

data class SyncProgress(
    val phase: SyncPhase,
    val message: String,
    val current: Int = 0,
    val total: Int = 0,
    val details: String? = null
)

enum class SyncPhase { 
    IDLE, 
    PULLING, 
    MERGING, 
    PUSHING, 
    RESOLVING_CONFLICTS, 
    COMPLETE, 
    ERROR 
}

data class SyncLogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val phase: SyncPhase,
    val dataType: DataType,
    val message: String,
    val count: Int,
    val detailsJson: String? = null
)

enum class DataType { INBOX, AW_BUCKETS, AW_EVENTS }

// ==================== 冲突解决 ====================

data class ConflictResolution(
    val noteId: Long,
    val resolution: ResolutionType,
    val mergedContent: String? = null,
    val mergedTags: List<String>? = null
)

enum class ResolutionType { SERVER, LOCAL, MERGED, KEEP_BOTH }