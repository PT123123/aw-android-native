package net.activitywatch.android.sync

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// aw-sync-rust REST API，挂载于 /api/0/sync（共 17 个端点）
interface SyncApi {

    // ---- 设置 ----

    @GET("api/0/sync/config")
    suspend fun getConfig(): SyncConfig

    @PUT("api/0/sync/config")
    suspend fun saveConfig(@Body config: SyncConfig): SyncConfig

    @GET("api/0/sync/info")
    suspend fun getInfo(): Device

    // ---- 配对 ----

    @POST("api/0/sync/pair/initiate")
    suspend fun initiatePair(@Body request: PairRequest): OpResult

    @POST("api/0/sync/pair/accept")
    suspend fun acceptPair(@Body request: PairRequest): OpResult

    // ---- 设备 ----

    @GET("api/0/sync/devices")
    suspend fun getDevices(): List<Device>

    @POST("api/0/sync/devices")
    suspend fun addDevice(@Body device: Device): OpResult

    @DELETE("api/0/sync/devices/all")
    suspend fun clearAllDevices(): OpResult

    @DELETE("api/0/sync/devices/{id}")
    suspend fun removeDevice(@Path("id") id: String): OpResult

    @PUT("api/0/sync/devices/{id}/alias")
    suspend fun updateDeviceAlias(@Path("id") id: String, @Body request: AliasRequest): OpResult

    @POST("api/0/sync/devices/{id}/sync")
    suspend fun syncDevice(@Path("id") id: String): SyncResult

    // ---- WiFi 热点传输（实验性） ----

    /** 导出本机快照（activity / inbox / todo 按「设置」里的同步目标裁剪） */
    @GET("api/0/sync/snapshot")
    suspend fun getSnapshot(): SyncSnapshot

    /** 把「从对端拉来的快照」合并进本机（复用服务端 apply_snapshot：幂等 upsert + 冲突处理） */
    @POST("api/0/sync/apply")
    suspend fun applySnapshot(@Body snapshot: SyncSnapshot): SyncResult

    @GET("api/0/sync/devices/{id}/stats")
    suspend fun getDeviceStats(@Path("id") id: String): DeviceSyncStats

    @GET("api/0/sync/devices/{id}/conflicts")
    suspend fun getDeviceConflicts(@Path("id") id: String): ConflictsResponse

    // ---- 回收站（冲突归档，P0）----

    @GET("api/0/sync/trash")
    suspend fun getTrash(@Query("kind") kind: String? = null): TrashResponse

    @POST("api/0/sync/trash/{id}/restore")
    suspend fun restoreTrash(@Path("id") id: Long): TrashOpResult

    @DELETE("api/0/sync/trash/{id}")
    suspend fun deleteTrash(@Path("id") id: Long): TrashOpResult

    @DELETE("api/0/sync/trash")
    suspend fun clearTrash(): TrashOpResult

    // ---- 状态与日志 ----

    @GET("api/0/sync/status")
    suspend fun getStatus(): DiscoveryStatus

    @GET("api/0/sync/log")
    suspend fun getLogs(
        @Query("direction") direction: String?,
        @Query("event_type") eventType: String?,
        @Query("protocol") protocol: String?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): LogPage

    @DELETE("api/0/sync/log")
    suspend fun clearLogs(): OpResult

    // Rust 侧调试日志（环形缓冲）增量拉取，after = 上次收到的最大 seq
    @GET("api/0/sync/debuglog")
    suspend fun getDebugLog(@Query("after") after: Int): List<DebugEntry>
}
