package net.activitywatch.android.sync

import retrofit2.Response
import retrofit2.http.*

interface InboxApi {
    @POST("/inbox/sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>

    @GET("/inbox/sync/devices")
    suspend fun getDevices(): Response<DeviceListResponse>

    @POST("/inbox/sync/devices/heartbeat")
    suspend fun heartbeat(@Body request: DeviceHeartbeat): Response<Unit>
}