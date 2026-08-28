package net.activitywatch.android.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.distinctUntilChanged
import net.activitywatch.android.db.*
import org.threeten.bp.Instant
import java.util.concurrent.atomic.AtomicBoolean

class SyncEngine(
    private val context: Context,
    private val api: InboxApi,
    private val database: InboxDatabase
) {
    private val TAG = "SyncEngine"
    private val _syncProgress = MutableLiveData<SyncProgress>()
    val syncProgress: LiveData<SyncProgress> = _syncProgress

    private val _syncState = MutableLiveData<SyncState>()
    val syncState: LiveData<SyncState> = _syncState

    private val _devices = MutableLiveData<List<SyncDevice>>()
    val devices: LiveData<List<SyncDevice>> = _devices

    private val _conflicts = MutableLiveData<List<SyncConflictEntity>>()
    val conflicts: LiveData<List<SyncConflictEntity>> = _conflicts

    private val isSyncing = AtomicBoolean(false)
    private var syncJob: Job? = null
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    init {
        observeState()
        observeDevices()
        observeConflicts()
    }

    private fun observeState() {
        database.syncStateDao().getFlow(deviceId)
            .distinctUntilChanged()
            .onEach { entity ->
                entity?.let { _syncState.postValue(it.toSyncState()) }
            }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    private fun observeDevices() {
        database.syncDeviceDao().getAllFlow()
            .distinctUntilChanged()
            .onEach { entities ->
                _devices.postValue(entities.map { it.toSyncDevice() })
            }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    private fun observeConflicts() {
        database.syncConflictDao().getUnresolvedFlow()
            .distinctUntilChanged()
            .onEach { _conflicts.postValue(it) }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    // ==================== 公共接口 ====================

    fun startSync(): Boolean {
        if (isSyncing.getAndSet(true)) {
            Log.d(TAG, "Already syncing, skipping")
            return false
        }
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                performSync()
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                emitProgress(SyncProgress(SyncPhase.ERROR, "同步失败: ${e.message}", details = e.toString()))
                updateErrorState(e.message)
            } finally {
                isSyncing.set(false)
            }
        }
        return true
    }

    fun cancelSync() {
        syncJob?.cancel()
        isSyncing.set(false)
    }

    fun resolveConflict(resolution: ConflictResolution) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                applyConflictResolution(resolution)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve conflict", e)
            }
        }
    }

    fun resolveAllConflicts(resolutions: List<ConflictResolution>) {
        CoroutineScope(Dispatchers.IO).launch {
            resolutions.forEach { applyConflictResolution(it) }
        }
    }

    fun forceFullSync() {
        CoroutineScope(Dispatchers.IO).launch {
            val stateDao = database.syncStateDao()
            val state = stateDao.get(deviceId)?.toSyncState() ?: SyncState(deviceId = deviceId)
            val newState = state.copy(lastFullSyncAt = null, serverVersion = 0, vectorClock = emptyMap())
            stateDao.upsert(SyncStateEntity.fromSyncState(newState))
            startSync()
        }
    }

    // ==================== 核心同步逻辑 ====================

    private suspend fun performSync() {
        val stateDao = database.syncStateDao()
        val noteDao = database.inboxNoteDao()
        val logDao = database.syncLogDao()
        val mapDao = database.noteSyncMapDao()
        val deviceDao = database.syncDeviceDao()

        val state = stateDao.get(deviceId)?.toSyncState() ?: SyncState(deviceId = deviceId)
        
        // 决定是否全量同步
        val shouldFullSync = shouldFullSync(state)
        val baseVersion = if (shouldFullSync) 0 else state.serverVersion

        emitProgress(SyncProgress(SyncPhase.PULLING, if (shouldFullSync) "全量同步中..." else "检查更新...", 0, 1))
        logSync(SyncPhase.PULLING, DataType.INBOX, if (shouldFullSync) "开始全量同步" else "开始增量同步", 0)

        // 收集本地待推送变更
        val pushChanges = collectPushChanges()
        
        // 构建请求
        val request = SyncRequest(
            deviceId = deviceId,
            baseVersion = baseVersion,
            deviceVersions = state.vectorClock,
            lastFullSyncAt = state.lastFullSyncAt,
            pushChanges = pushChanges,
            pullLimit = 500
        )

        // 发起同步请求
        val apiResponse = api.sync(request)
        val response = apiResponse.body() ?: throw Exception("Sync response body is null")
        
        emitProgress(SyncProgress(SyncPhase.MERGING, "合并 ${response.pulledNotes.size} 条数据...", 0, 1))
        logSync(SyncPhase.MERGING, DataType.INBOX, "拉取 ${response.pulledNotes.size} 条笔记", response.pulledNotes.size)

        // 处理拉取的笔记
        mergePulledNotes(response.pulledNotes, shouldFullSync)

        // 处理推送结果
        processPushResults(response.pushResults)

        // 处理冲突
        if (response.conflicts.isNotEmpty()) {
            emitProgress(SyncProgress(SyncPhase.RESOLVING_CONFLICTS, "${response.conflicts.size} 个冲突待解决", 0, 1))
            logSync(SyncPhase.RESOLVING_CONFLICTS, DataType.INBOX, "${response.conflicts.size} 个冲突", response.conflicts.size)
            storeConflicts(response.conflicts)
        }

        // 更新同步状态
        val newState = state.copy(
            serverVersion = response.currentVersion,
            vectorClock = response.deviceStates.mapValues { it.value.version },
            lastFullSyncAt = if (shouldFullSync) System.currentTimeMillis() else state.lastFullSyncAt,
            lastSyncAt = System.currentTimeMillis(),
            pendingPushCount = noteDao.getPendingCount(),
            pendingConflictCount = response.conflicts.size,
            lastError = null
        )
        stateDao.upsert(SyncStateEntity.fromSyncState(newState))

        // 更新设备状态
        updateDeviceStates(response.deviceStates)

        emitProgress(SyncProgress(SyncPhase.COMPLETE, "同步完成", 1, 1))
        logSync(SyncPhase.COMPLETE, DataType.INBOX, "同步完成，版本 ${response.currentVersion}", 1)
    }

    private fun shouldFullSync(state: SyncState): Boolean {
        return state.serverVersion == 0L ||  // 首次同步
               state.lastFullSyncAt == null ||  // 从未全量
               (System.currentTimeMillis() - (state.lastFullSyncAt ?: 0)) > 30L * 24 * 60 * 60 * 1000  // 超过30天
    }

    private suspend fun collectPushChanges(): List<PushChange> {
        val noteDao = database.inboxNoteDao()
        val changes = mutableListOf<PushChange>()
        var localVersionCounter = System.currentTimeMillis()

        // 待创建/更新的笔记
        val pendingNotes = noteDao.getPendingCreateOrUpdate()
        for (note in pendingNotes) {
            val localNote = note.toLocalNote()
            val pushChange = if (note.serverId == null) {
                // 新建
                localVersionCounter++
                PushChange(
                    type = ChangeType.CREATE,
                    localVersion = localVersionCounter,
                    note = localNote.toInboxNote()
                )
            } else {
                // 更新
                localVersionCounter++
                PushChange(
                    type = ChangeType.UPDATE,
                    noteId = note.serverId,
                    expectedVersion = note.version,
                    localVersion = localVersionCounter,
                    fields = localNote.toInboxNote().toMap()
                )
            }
            changes.add(pushChange)
        }

        // 待删除的笔记
        val pendingDeletes = noteDao.getPendingDeletes()
        for (note in pendingDeletes) {
            if (note.serverId != null) {
                localVersionCounter++
                changes.add(PushChange(
                    type = ChangeType.DELETE,
                    noteId = note.serverId,
                    expectedVersion = note.version,
                    localVersion = localVersionCounter
                ))
            }
        }

        return changes
    }

    private suspend fun mergePulledNotes(pulledNotes: List<InboxNote>, isFullSync: Boolean) {
        val noteDao = database.inboxNoteDao()
        val mapDao = database.noteSyncMapDao()

        val toInsert = mutableListOf<InboxNoteEntity>()
        val toUpdate = mutableListOf<InboxNoteEntity>()

        for (serverNote in pulledNotes) {
            if (serverNote.deleted) {
                // 服务端删除：本地标记删除并同步
                val existing = noteDao.getByServerId(serverNote.id!!)
                existing?.let {
                    it.deleted = true
                    it.pendingSync = false
                    it.syncedAt = System.currentTimeMillis()
                    it.version = serverNote.version
                    toUpdate.add(it)
                }
                continue
            }

            val existing = noteDao.getByServerId(serverNote.id!!)
            val localNote = existing?.toLocalNote()

            val shouldUpdate = when {
                existing == null -> true
                localNote!!.deleted && !serverNote.deleted -> true  // 本地删了但服务端恢复了
                localNote.version < serverNote.version -> true       // 服务端版本更新
                localNote.version == serverNote.version && localNote.updatedAt < parseTime(serverNote.updatedAt) -> true
                else -> false
            }

            if (shouldUpdate) {
                val entity = serverNote.toEntity(deviceId)
                entity.pendingSync = false
                entity.syncedAt = System.currentTimeMillis()
                
                if (existing == null) {
                    entity.pendingSync = false
                    toInsert.add(entity)
                } else {
                    entity.id = existing.id
                    entity.localVersion = existing.localVersion
                    toUpdate.add(entity)
                }

                // 更新映射表
                if (serverNote.id != null) {
                    mapDao.upsert(NoteSyncMapEntity(
                        localId = entity.id,
                        serverId = serverNote.id,
                        status = SyncMapStatus.SYNCED.name,
                        lastPush = System.currentTimeMillis()
                    ))
                }
            }
        }

        if (toInsert.isNotEmpty()) noteDao.insertAll(toInsert)
        if (toUpdate.isNotEmpty()) noteDao.updateAll(toUpdate)
    }

    private suspend fun processPushResults(results: List<PushResult>) {
        val noteDao = database.inboxNoteDao()
        val mapDao = database.noteSyncMapDao()

        for (result in results) {
            when (result.status) {
                PushStatus.CREATED -> {
                    result.localVersion?.let { localVer ->
                        val pendingNotes = noteDao.getPendingCreateOrUpdate()
                        val matching = pendingNotes.find { it.localVersion == localVer }
                        matching?.let { note ->
                            note.serverId = result.noteId
                            note.version = result.serverVersion ?: 0
                            note.pendingSync = false
                            note.syncedAt = System.currentTimeMillis()
                            noteDao.update(note)
                            
                            mapDao.upsert(NoteSyncMapEntity(
                                localId = note.id,
                                serverId = result.noteId,
                                status = SyncMapStatus.SYNCED.name,
                                lastPush = System.currentTimeMillis()
                            ))
                        }
                    }
                }
                PushStatus.UPDATED -> {
                    result.noteId?.let { serverId ->
                        val note = noteDao.getByServerId(serverId)
                        note?.let {
                            it.version = result.serverVersion ?: 0
                            it.pendingSync = false
                            it.syncedAt = System.currentTimeMillis()
                            noteDao.update(it)
                            
                            mapDao.upsert(NoteSyncMapEntity(
                                localId = it.id,
                                serverId = serverId,
                                status = SyncMapStatus.SYNCED.name,
                                lastPush = System.currentTimeMillis()
                            ))
                        }
                    }
                }
                PushStatus.DELETED -> {
                    result.noteId?.let { serverId ->
                        val note = noteDao.getByServerId(serverId)
                        note?.let {
                            it.deleted = true
                            it.pendingSync = false
                            it.syncedAt = System.currentTimeMillis()
                            it.version = result.serverVersion ?: 0
                            noteDao.update(it)
                        }
                    }
                }
                PushStatus.CONFLICT -> {
                    // 冲突已在 storeConflicts 中处理
                    result.noteId?.let { serverId ->
                        mapDao.upsert(NoteSyncMapEntity(
                            localId = noteDao.getByServerId(serverId)?.id ?: 0,
                            serverId = serverId,
                            status = SyncMapStatus.CONFLICT.name,
                            lastPush = System.currentTimeMillis()
                        ))
                    }
                }
                PushStatus.ERROR -> {
                    // 错误状态，记录日志但不处理
                    Log.w(TAG, "Push error for localVersion: ${result.localVersion}")
                }
            }
        }
    }

    private suspend fun storeConflicts(conflicts: List<SyncConflict>) {
        val conflictDao = database.syncConflictDao()
        for (conflict in conflicts) {
            val entity = SyncConflictEntity(
                noteId = conflict.noteId,
                serverVersion = conflict.serverVersion,
                deviceVersions = com.google.gson.Gson().toJson(mapOf(deviceId to conflict.clientExpectedVersion)),
                detectedAt = System.currentTimeMillis(),
                serverNoteJson = com.google.gson.Gson().toJson(conflict.serverNote),
                clientNoteJson = com.google.gson.Gson().toJson(conflict.clientNote),
                commonAncestorVersion = conflict.commonAncestorVersion
            )
            conflictDao.upsert(entity)
        }
    }

    private suspend fun applyConflictResolution(resolution: ConflictResolution) {
        val noteDao = database.inboxNoteDao()
        val conflictDao = database.syncConflictDao()
        val mapDao = database.noteSyncMapDao()

        val conflict = conflictDao.get(resolution.noteId) ?: return
        val serverNote = com.google.gson.Gson().fromJson(conflict.serverNoteJson, InboxNote::class.java)
        val clientNote = com.google.gson.Gson().fromJson(conflict.clientNoteJson, InboxNote::class.java)

        val resolvedNote = when (resolution.resolution) {
            ResolutionType.SERVER -> serverNote
            ResolutionType.LOCAL -> clientNote
            ResolutionType.MERGED -> mergeNotes(serverNote, clientNote, resolution)
            ResolutionType.KEEP_BOTH -> {
                // 创建两个笔记：服务端版本保留原ID，本地版本新建
                val localCopy = clientNote.copy(id = null)
                noteDao.insert(InboxNoteEntity.fromLocalNote(localCopy.toLocalNote(deviceId)))
                serverNote
            }
        }

        // 应用解决后的笔记
        val existing = noteDao.getByServerId(resolution.noteId)
        val entity = resolvedNote.toEntity(deviceId).apply {
            id = existing?.id ?: 0
            version = conflict.serverVersion
            pendingSync = true  // 需要推送解决结果
            syncedAt = null
        }

        if (existing != null) {
            noteDao.update(entity)
        } else {
            val newId = noteDao.insert(entity)
            mapDao.upsert(NoteSyncMapEntity(
                localId = newId,
                serverId = resolution.noteId,
                status = SyncMapStatus.PENDING.name
            ))
        }

        conflictDao.markResolved(resolution.noteId, System.currentTimeMillis(), resolution.resolution.name)
    }

    private fun mergeNotes(server: InboxNote, client: InboxNote, resolution: ConflictResolution): InboxNote {
        return server.copy(
            content = resolution.mergedContent ?: client.content,
            tags = resolution.mergedTags ?: (server.tags + client.tags).distinct(),
            updatedAt = Instant.now().toString(),
            conflict = false
        )
    }

    private suspend fun updateDeviceStates(deviceStates: Map<String, DeviceState>) {
        val deviceDao = database.syncDeviceDao()
        val entities = deviceStates.map { (id, state) ->
            val existing = deviceDao.get(id)
            SyncDeviceEntity(
                deviceId = id,
                name = existing?.name ?: id,
                platform = existing?.platform ?: "unknown",
                lastSeenAt = parseTime(state.lastSeen),
                lastSyncedAt = parseTime(state.lastSeen),
                pendingChanges = state.pending,
                version = state.version,
                isCurrent = id == deviceId,
                status = if (state.pending > 0) DeviceStatus.CONFLICT.name else DeviceStatus.ONLINE.name
            )
        }
        deviceDao.upsertAll(entities)
    }

    private suspend fun updateErrorState(error: String?) {
        val stateDao = database.syncStateDao()
        val state = stateDao.get(deviceId)?.toSyncState() ?: SyncState(deviceId = deviceId)
        stateDao.upsert(SyncStateEntity.fromSyncState(state.copy(lastError = error)))
    }

    private fun emitProgress(progress: SyncProgress) {
        _syncProgress.postValue(progress)
    }

    private suspend fun logSync(phase: SyncPhase, dataType: DataType, message: String, count: Int, details: String? = null) {
        val log = SyncLogEntity(
            timestamp = System.currentTimeMillis(),
            phase = phase.name,
            dataType = dataType.name,
            message = message,
            count = count,
            detailsJson = details
        )
        database.syncLogDao().insert(log)
    }

    private fun parseTime(isoString: String): Long {
        return try {
            Instant.parse(isoString).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // ==================== 本地笔记操作 ====================

    suspend fun createNote(content: String, tags: List<String> = emptyList()): Long {
        val noteDao = database.inboxNoteDao()
        val now = System.currentTimeMillis()
        val entity = InboxNoteEntity(
            content = content,
            timestamp = now,
            updatedAt = now,
            tags = com.google.gson.Gson().toJson(tags),
            deviceId = deviceId,
            pendingSync = true,
            localVersion = System.currentTimeMillis()
        )
        return noteDao.insert(entity)
    }

    suspend fun updateNote(id: Long, content: String? = null, tags: List<String>? = null) {
        val noteDao = database.inboxNoteDao()
        val entity = noteDao.getById(id) ?: return
        
        val newContent = content ?: entity.content
        val newTags = tags?.let { com.google.gson.Gson().toJson(it) } ?: entity.tags
        val now = System.currentTimeMillis()

        entity.content = newContent
        entity.tags = newTags
        entity.updatedAt = now
        entity.pendingSync = true
        entity.localVersion = System.currentTimeMillis()
        
        noteDao.update(entity)
    }

    suspend fun deleteNote(id: Long) {
        val noteDao = database.inboxNoteDao()
        val entity = noteDao.getById(id) ?: return
        
        entity.deleted = true
        entity.updatedAt = System.currentTimeMillis()
        entity.pendingSync = true
        entity.localVersion = System.currentTimeMillis()
        
        noteDao.update(entity)
    }

    suspend fun getNotes(limit: Int = 50): List<LocalNote> {
        val noteDao = database.inboxNoteDao()
        return noteDao.getAll(limit).map { it.toLocalNote() }
    }

    companion object {
        private const val FULL_SYNC_INTERVAL_DAYS = 30
    }
}