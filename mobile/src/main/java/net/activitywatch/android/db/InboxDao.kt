package net.activitywatch.android.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: InboxNoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<InboxNoteEntity>)

    @Update
    suspend fun update(note: InboxNoteEntity)

    @Update
    suspend fun updateAll(notes: List<InboxNoteEntity>)

    @Delete
    suspend fun delete(note: InboxNoteEntity)

    @Query("DELETE FROM inbox_notes WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Long): Int

    @Query("SELECT * FROM inbox_notes WHERE id = :id")
    suspend fun getById(id: Long): InboxNoteEntity?

    @Query("SELECT * FROM inbox_notes WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): InboxNoteEntity?

    @Query("SELECT * FROM inbox_notes WHERE deleted = 0 ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<InboxNoteEntity>

    @Query("SELECT * FROM inbox_notes WHERE deleted = 0 AND updated_at > :since ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int): List<InboxNoteEntity>

    @Query("SELECT * FROM inbox_notes WHERE pending_sync = 1")
    suspend fun getPendingSync(): List<InboxNoteEntity>

    @Query("SELECT * FROM inbox_notes WHERE pending_sync = 1 AND deleted = 0")
    suspend fun getPendingCreateOrUpdate(): List<InboxNoteEntity>

    @Query("SELECT * FROM inbox_notes WHERE pending_sync = 1 AND deleted = 1")
    suspend fun getPendingDeletes(): List<InboxNoteEntity>

    @Query("SELECT * FROM inbox_notes WHERE device_id = :deviceId AND deleted = 0 ORDER BY updated_at DESC")
    suspend fun getByDeviceId(deviceId: String): List<InboxNoteEntity>

    @Query("SELECT MAX(version) FROM inbox_notes")
    suspend fun getMaxVersion(): Long?

    @Query("SELECT COUNT(*) FROM inbox_notes WHERE pending_sync = 1")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM inbox_notes WHERE deleted = 0")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM inbox_notes WHERE deleted = 1 AND synced_at IS NOT NULL AND synced_at < :before")
    suspend fun cleanupOldDeleted(before: Long): Int
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE device_id = :deviceId")
    suspend fun get(deviceId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE device_id = :deviceId")
    fun getFlow(deviceId: String): Flow<SyncStateEntity?>

    @Query("DELETE FROM sync_state WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String): Int
}

@Dao
interface SyncDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: SyncDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<SyncDeviceEntity>)

    @Query("SELECT * FROM sync_devices ORDER BY is_current DESC, last_seen_at DESC")
    suspend fun getAll(): List<SyncDeviceEntity>

    @Query("SELECT * FROM sync_devices ORDER BY is_current DESC, last_seen_at DESC")
    fun getAllFlow(): Flow<List<SyncDeviceEntity>>

    @Query("SELECT * FROM sync_devices WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): SyncDeviceEntity?

    @Query("SELECT * FROM sync_devices WHERE is_current = 1")
    suspend fun getCurrent(): SyncDeviceEntity?

    @Query("UPDATE sync_devices SET last_seen_at = :lastSeen, pending_changes = :pending, version = :version, status = :status, updated_at = :updated WHERE deviceId = :deviceId")
    suspend fun updateStatus(deviceId: String, lastSeen: Long, pending: Int, version: Long, status: String, updated: Long)

    @Query("DELETE FROM sync_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String): Int
}

@Dao
interface SyncConflictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conflict: SyncConflictEntity)

    @Query("SELECT * FROM sync_conflicts WHERE resolved_at IS NULL")
    suspend fun getUnresolved(): List<SyncConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE noteId = :noteId")
    suspend fun get(noteId: Long): SyncConflictEntity?

    @Query("SELECT * FROM sync_conflicts WHERE resolved_at IS NULL ORDER BY detected_at DESC")
    fun getUnresolvedFlow(): Flow<List<SyncConflictEntity>>

    @Query("UPDATE sync_conflicts SET resolved_at = :resolvedAt, resolution = :resolution WHERE noteId = :noteId")
    suspend fun markResolved(noteId: Long, resolvedAt: Long, resolution: String): Int

    @Query("DELETE FROM sync_conflicts WHERE noteId = :noteId")
    suspend fun delete(noteId: Long): Int

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE resolved_at IS NULL")
    suspend fun getUnresolvedCount(): Int
}

@Dao
interface SyncLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: SyncLogEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(logs: List<SyncLogEntity>)

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SyncLogEntity>

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_log WHERE data_type = :dataType ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByDataType(dataType: String, limit: Int): List<SyncLogEntity>

    @Query("DELETE FROM sync_log WHERE timestamp < :before")
    suspend fun cleanupOld(before: Long): Int

    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun getTotalCount(): Int
}

@Dao
interface NoteSyncMapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(map: NoteSyncMapEntity)

    @Query("SELECT * FROM note_sync_map WHERE localId = :localId")
    suspend fun getByLocalId(localId: Long): NoteSyncMapEntity?

    @Query("SELECT * FROM note_sync_map WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: Long): NoteSyncMapEntity?

    @Query("SELECT * FROM note_sync_map WHERE status = :status")
    suspend fun getByStatus(status: String): List<NoteSyncMapEntity>

    @Query("SELECT * FROM note_sync_map WHERE status = :status")
    fun getByStatusFlow(status: String): Flow<List<NoteSyncMapEntity>>

    @Query("UPDATE note_sync_map SET status = :status, last_push = :lastPush, retry_count = retry_count + 1 WHERE localId = :localId")
    suspend fun updateStatus(localId: Long, status: String, lastPush: Long): Int

    @Query("DELETE FROM note_sync_map WHERE localId = :localId")
    suspend fun delete(localId: Long): Int
}