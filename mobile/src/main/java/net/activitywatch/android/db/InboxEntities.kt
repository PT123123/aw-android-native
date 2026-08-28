package net.activitywatch.android.db

import androidx.room.*
import net.activitywatch.android.sync.*

@Entity(tableName = "inbox_notes", indices = [
    Index(value = ["server_id"], unique = true),
    Index(value = ["updated_at"]),
    Index(value = ["pending_sync"]),
    Index(value = ["device_id"])
])
data class InboxNoteEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    @ColumnInfo(name = "server_id") var serverId: Long? = null,
    @ColumnInfo(name = "content") var content: String,
    @ColumnInfo(name = "timestamp") var timestamp: Long,
    @ColumnInfo(name = "updated_at") var updatedAt: Long,
    @ColumnInfo(name = "tags") var tags: String,  // JSON string
    @ColumnInfo(name = "version") var version: Long = 0,
    @ColumnInfo(name = "device_id") var deviceId: String,
    @ColumnInfo(name = "deleted") var deleted: Boolean = false,
    @ColumnInfo(name = "synced_at") var syncedAt: Long? = null,
    @ColumnInfo(name = "pending_sync") var pendingSync: Boolean = true,
    @ColumnInfo(name = "local_version") var localVersion: Long = 0
) {
    fun toLocalNote(): LocalNote = LocalNote(
        id = id,
        serverId = serverId,
        content = content,
        timestamp = timestamp,
        updatedAt = updatedAt,
        tags = tagsToList(),
        version = version,
        deviceId = deviceId,
        deleted = deleted,
        syncedAt = syncedAt,
        pendingSync = pendingSync,
        localVersion = localVersion
    )

    private fun tagsToList(): List<String> {
        return try {
            com.google.gson.Gson().fromJson(tags, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        fun fromLocalNote(note: LocalNote): InboxNoteEntity {
            val tagsJson = com.google.gson.Gson().toJson(note.tags)
            return InboxNoteEntity(
                id = note.id,
                serverId = note.serverId,
                content = note.content,
                timestamp = note.timestamp,
                updatedAt = note.updatedAt,
                tags = tagsJson,
                version = note.version,
                deviceId = note.deviceId,
                deleted = note.deleted,
                syncedAt = note.syncedAt,
                pendingSync = note.pendingSync,
                localVersion = note.localVersion
            )
        }
    }
}

@Entity(tableName = "sync_state", primaryKeys = ["device_id"])
data class SyncStateEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "server_version") val serverVersion: Long = 0,
    @ColumnInfo(name = "vector_clock") val vectorClock: String,  // JSON
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "pending_push_count") val pendingPushCount: Int = 0,
    @ColumnInfo(name = "pending_conflict_count") val pendingConflictCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    fun toSyncState(): SyncState {
        return SyncState(
            deviceId = deviceId,
            serverVersion = serverVersion,
            vectorClock = try {
                com.google.gson.Gson().fromJson(vectorClock, object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type)
            } catch (e: Exception) {
                emptyMap()
            },
            lastFullSyncAt = lastFullSyncAt,
            lastSyncAt = lastSyncAt,
            pendingPushCount = pendingPushCount,
            pendingConflictCount = pendingConflictCount,
            lastError = lastError
        )
    }

    companion object {
        fun fromSyncState(state: SyncState): SyncStateEntity {
            val vectorClockJson = com.google.gson.Gson().toJson(state.vectorClock)
            return SyncStateEntity(
                deviceId = state.deviceId,
                serverVersion = state.serverVersion,
                vectorClock = vectorClockJson,
                lastFullSyncAt = state.lastFullSyncAt,
                lastSyncAt = state.lastSyncAt,
                pendingPushCount = state.pendingPushCount,
                pendingConflictCount = state.pendingConflictCount,
                lastError = state.lastError,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

@Entity(tableName = "sync_devices", indices = [Index(value = ["is_current"], unique = true)])
data class SyncDeviceEntity(
    @PrimaryKey val deviceId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "pending_changes") val pendingChanges: Int = 0,
    @ColumnInfo(name = "version") val version: Long = 0,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean = false,
    @ColumnInfo(name = "status") val status: String = DeviceStatus.OFFLINE.name,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    fun toSyncDevice(): SyncDevice = SyncDevice(
        deviceId = deviceId,
        name = name,
        platform = platform,
        lastSeenAt = lastSeenAt,
        lastSyncedAt = lastSyncedAt,
        pendingChanges = pendingChanges,
        version = version,
        isCurrent = isCurrent,
        status = DeviceStatus.valueOf(status)
    )

    companion object {
        fun fromSyncDevice(device: SyncDevice): SyncDeviceEntity {
            return SyncDeviceEntity(
                deviceId = device.deviceId,
                name = device.name,
                platform = device.platform,
                lastSeenAt = device.lastSeenAt,
                lastSyncedAt = device.lastSyncedAt,
                pendingChanges = device.pendingChanges,
                version = device.version,
                isCurrent = device.isCurrent,
                status = device.status.name,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey val noteId: Long,
    @ColumnInfo(name = "server_version") val serverVersion: Long,
    @ColumnInfo(name = "device_versions") val deviceVersions: String,  // JSON Map<deviceId, NoteVersion>
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
    @ColumnInfo(name = "resolution") val resolution: String? = null,
    @ColumnInfo(name = "server_note_json") val serverNoteJson: String,
    @ColumnInfo(name = "client_note_json") val clientNoteJson: String,
    @ColumnInfo(name = "common_ancestor_version") val commonAncestorVersion: Long
)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "data_type") val dataType: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "details_json") val detailsJson: String? = null
) {
    fun toSyncLogEntry(): SyncLogEntry = SyncLogEntry(
        id = id,
        timestamp = timestamp,
        phase = SyncPhase.valueOf(phase),
        dataType = DataType.valueOf(dataType),
        message = message,
        count = count,
        detailsJson = detailsJson
    )

    companion object {
        fun fromSyncLogEntry(entry: SyncLogEntry): SyncLogEntity {
            return SyncLogEntity(
                id = entry.id,
                timestamp = entry.timestamp,
                phase = entry.phase.name,
                dataType = entry.dataType.name,
                message = entry.message,
                count = entry.count,
                detailsJson = entry.detailsJson
            )
        }
    }
}

@Entity(tableName = "note_sync_map")
data class NoteSyncMapEntity(
    @PrimaryKey val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "status") val status: String = SyncMapStatus.PENDING.name,
    @ColumnInfo(name = "last_push") val lastPush: Long? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0
)

enum class SyncMapStatus { PENDING, SYNCED, CONFLICT, ERROR }