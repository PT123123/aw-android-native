package net.activitywatch.android.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SyncUiState(
    val initialLoading: Boolean = true,
    val config: SyncConfig? = null,
    val status: DiscoveryStatus? = null,
    val devices: List<Device> = emptyList(),
    val deviceStats: Map<String, DeviceSyncStats> = emptyMap(),
    val deviceConflicts: Map<String, List<ConflictSummary>> = emptyMap(),
    val logs: List<SyncLogEntry> = emptyList(),
    val logsTotal: Int = 0,
    val logError: String? = null,
    val filterDirection: String = "",
    val filterEventType: String = "",
    val filterProtocol: String = "",
    val pageSize: Int = 10,
    val refreshSeconds: Int = 5,
    val expandedDevices: Set<String> = emptySet(),
    val busyDevices: Set<String> = emptySet(),
    val renamingDeviceId: String? = null,
    val savingConfig: Boolean = false
) {
    val selfDevice: Device?
        get() = devices.firstOrNull { it.isSelf } ?: status?.selfDevice

    val discoveredDevices: List<Device>
        get() = devices.filter { !it.isSelf && !it.paired && it.isEffectivelyOnline }

    val pairedDevices: List<Device>
        get() = devices.filter { it.paired }
}

// 局域网同步页状态与动作
class SyncViewModel : ViewModel() {

    private val repo = SyncRepository()
    private val api get() = repo.api

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.UNLIMITED)
    val messages = _messages.receiveAsFlow()

    private var debugPollSeq = 0
    private var debugWarned = false

    private fun toast(msg: String) {
        _messages.trySend(msg)
    }

    // ==================== 轮询 ====================

    // 由 Fragment 在 repeatOnLifecycle(STARTED) 中启动；离开页面协程即取消，轮询停止
    suspend fun poll() = coroutineScope {
        launch { mainLoop() }
        launch { debugLogLoop() }
    }

    private suspend fun mainLoop() {
        refreshInitial()
        while (currentCoroutineContext().isActive) {
            delay(_state.value.refreshSeconds * 1000L)
            refreshDevices()
            refreshLogs()
            refreshStatus()
        }
    }

    private suspend fun refreshInitial() {
        coroutineScope {
            launch { refreshConfig() }
            launch { refreshDevices() }
            launch { refreshLogs() }
            launch { refreshStatus() }
        }
        _state.update { it.copy(initialLoading = false) }
    }

    private suspend fun refreshConfig() {
        repo.call { api.getConfig() }
            .onSuccess { cfg -> _state.update { it.copy(config = cfg) } }
    }

    private suspend fun refreshStatus() {
        repo.call { api.getStatus() }
            .onSuccess { st -> _state.update { it.copy(status = st) } }
    }

    private suspend fun refreshDevices() {
        repo.call { api.getDevices() }.onSuccess { devices ->
            _state.update { it.copy(devices = devices) }
            // 已配对的远端设备附带统计（与 vue 的 loadAllDeviceStats 一致）
            for (d in devices) {
                if (d.paired && !d.isSelf) loadDeviceStats(d.id)
            }
        }
    }

    private suspend fun loadDeviceStats(deviceId: String) {
        val stats = repo.call { api.getDeviceStats(deviceId) }.getOrNull() ?: return
        val conflicts = repo.call { api.getDeviceConflicts(deviceId) }.getOrNull()
        _state.update {
            it.copy(
                deviceStats = it.deviceStats + (deviceId to stats),
                deviceConflicts = it.deviceConflicts + (deviceId to (conflicts?.conflicts ?: emptyList()))
            )
        }
    }

    private suspend fun refreshLogs() {
        val s = _state.value
        repo.call {
            api.getLogs(
                direction = s.filterDirection.ifEmpty { null },
                eventType = s.filterEventType.ifEmpty { null },
                protocol = s.filterProtocol.ifEmpty { null },
                limit = s.pageSize,
                offset = 0
            )
        }.onSuccess { page ->
            _state.update { it.copy(logs = page.logs, logsTotal = page.total, logError = null) }
        }.onFailure { e ->
            _state.update { it.copy(logError = e.message) }
        }
    }

    // Rust 侧调试日志增量拉取，输出到 logcat（对应 vue 的 startLogPolling）
    private suspend fun debugLogLoop() {
        while (currentCoroutineContext().isActive) {
            repo.call { api.getDebugLog(debugPollSeq) }
                .onSuccess { entries ->
                    if (entries.isNotEmpty()) {
                        for (e in entries) {
                            Log.d(TAG, "[aw-sync][${e.level}] ${e.ts} ${e.msg}")
                        }
                        debugPollSeq = entries.last().seq
                    }
                }
                .onFailure { e ->
                    if (!debugWarned) {
                        debugWarned = true
                        Log.w(TAG, "调试日志拉取失败（若持续出现，检查内嵌 .so 是否为同一批构建）: ${e.message}")
                    }
                }
            delay(2000L)
        }
    }

    // ==================== 筛选与刷新设置 ====================

    fun setRefreshSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(1, 60)
        if (clamped == _state.value.refreshSeconds) return
        _state.update { it.copy(refreshSeconds = clamped) }
    }

    fun setFilterDirection(value: String) {
        _state.update { it.copy(filterDirection = value) }
        refreshLogsAsync()
    }

    fun setFilterEventType(value: String) {
        _state.update { it.copy(filterEventType = value) }
        refreshLogsAsync()
    }

    fun setFilterProtocol(value: String) {
        _state.update { it.copy(filterProtocol = value) }
        refreshLogsAsync()
    }

    fun setPageSize(value: Int) {
        _state.update { it.copy(pageSize = value) }
        refreshLogsAsync()
    }

    fun refreshNow() {
        viewModelScope.launch { refreshLogs() }
    }

    private fun refreshLogsAsync() {
        viewModelScope.launch { refreshLogs() }
    }

    // ==================== 配对 ====================

    fun initiatePair(deviceId: String) {
        viewModelScope.launch {
            repo.call { api.initiatePair(PairRequest(deviceId)) }
                .onSuccess {
                    afterPairChange()
                    toast("已发起配对请求，等待对方确认")
                }
                .onFailure { e ->
                    refreshLogs()
                    toast("配对失败：${e.message}")
                }
        }
    }

    fun acceptPair(deviceId: String) {
        viewModelScope.launch {
            repo.call { api.acceptPair(PairRequest(deviceId)) }
                .onSuccess {
                    afterPairChange()
                    toast("已接受配对")
                }
                .onFailure { e ->
                    refreshLogs()
                    toast("配对失败：${e.message}")
                }
        }
    }

    // 与 vue 一致：配对变更后重置筛选并刷新日志，确保新报文可见
    private suspend fun afterPairChange() {
        _state.update {
            it.copy(filterDirection = "", filterEventType = "", filterProtocol = "", pageSize = 10, refreshSeconds = 5)
        }
        refreshDevices()
        refreshLogs()
    }

    // ==================== 设备操作 ====================

    fun syncDevice(deviceId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyDevices = it.busyDevices + deviceId) }
            repo.call { api.syncDevice(deviceId) }
                .onSuccess { r ->
                    refreshLogs()
                    toast("同步完成，应用记录数 ${r.applied}")
                }
                .onFailure { e -> toast("同步失败：${e.message}") }
            _state.update { it.copy(busyDevices = it.busyDevices - deviceId) }
        }
    }

    /**
     * 立即同步全部已配对设备（标题栏刷新触发）。
     * 与 vue 的「立即同步」按钮语义一致：并发推给每台远端设备，最后统一刷新状态与日志。
     */
    fun syncAllPaired() {
        viewModelScope.launch {
            val targets = _state.value.pairedDevices.filter { !it.isSelf }
            if (targets.isEmpty()) {
                toast("还没有已配对的设备，先完成配对再同步")
                return@launch
            }
            val ids = targets.map { it.id }
            _state.update { it.copy(busyDevices = it.busyDevices + ids) }
            val results = coroutineScope {
                targets.map { d -> async { d to repo.call { api.syncDevice(d.id) } } }.awaitAll()
            }
            _state.update { it.copy(busyDevices = it.busyDevices - ids) }
            val applied = results.sumOf { (_, r) -> r.getOrNull()?.applied ?: 0 }
            val failed = results.count { (_, r) -> r.isFailure }
            results.forEach { (d, r) ->
                r.onFailure { e -> Log.w(TAG, "同步 ${d.displayName} 失败: ${e.message}") }
            }
            refreshDevices()
            refreshLogs()
            refreshStatus()
            toast(
                if (failed == 0) {
                    "已同步 ${targets.size} 台设备，应用记录 $applied 条"
                } else {
                    "同步完成：成功 ${targets.size - failed} 台，失败 $failed 台"
                }
            )
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyDevices = it.busyDevices + deviceId) }
            repo.call { api.removeDevice(deviceId) }
                .onSuccess { refreshDevices() }
                .onFailure { e -> toast("删除失败：${e.message}") }
            _state.update { it.copy(busyDevices = it.busyDevices - deviceId) }
        }
    }

    fun startRename(deviceId: String) {
        _state.update { it.copy(renamingDeviceId = deviceId) }
    }

    fun cancelRename() {
        _state.update { it.copy(renamingDeviceId = null) }
    }

    fun saveAlias(deviceId: String, alias: String, currentName: String) {
        val trimmed = alias.trim()
        if (trimmed.isEmpty() || trimmed == currentName) {
            _state.update { it.copy(renamingDeviceId = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busyDevices = it.busyDevices + deviceId) }
            repo.call { api.updateDeviceAlias(deviceId, AliasRequest(trimmed)) }
                .onSuccess {
                    _state.update { it.copy(renamingDeviceId = null) }
                    refreshDevices()
                }
                .onFailure { e -> toast("修改别名失败：${e.message}") }
            _state.update { it.copy(busyDevices = it.busyDevices - deviceId) }
        }
    }

    fun clearAllDevices() {
        viewModelScope.launch {
            repo.call { api.clearAllDevices() }
                .onSuccess { r ->
                    refreshDevices()
                    refreshLogs()
                    toast("已清空所有配对信息，共移除 ${r.cleared ?: 0} 台设备")
                }
                .onFailure { e -> toast("清空失败：${e.message}") }
        }
    }

    fun toggleDeviceDetails(deviceId: String) {
        _state.update {
            val set = it.expandedDevices
            it.copy(expandedDevices = if (deviceId in set) set - deviceId else set + deviceId)
        }
    }

    // ==================== 设置与日志 ====================

    fun saveConfig(config: SyncConfig) {
        viewModelScope.launch {
            _state.update { it.copy(savingConfig = true) }
            repo.call { api.saveConfig(config) }
                .onSuccess { saved ->
                    _state.update { it.copy(config = saved) }
                    refreshStatus()
                    toast(
                        if (saved.enabled) {
                            "同步设置已保存：局域网同步已开启，UDP 广播发现已启动（同网段设备将自动互相发现）"
                        } else {
                            "同步设置已保存（局域网同步处于关闭状态）"
                        }
                    )
                }
                .onFailure { e -> toast("保存失败: ${e.message}") }
            _state.update { it.copy(savingConfig = false) }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repo.call { api.clearLogs() }
                .onSuccess { refreshLogs() }
                .onFailure { e -> toast("清空日志失败：${e.message}") }
        }
    }

    companion object {
        private const val TAG = "SyncViewModel"
    }
}
