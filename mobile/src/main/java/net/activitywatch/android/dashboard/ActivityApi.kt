package net.activitywatch.android.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * 访问本机 aw-server-rust（固定监听 127.0.0.1:5600）的 REST API。
 * 与 inbox / sync 的 Retrofit 客户端保持一致，仅 baseUrl 多出 /api/0/ 前缀。
 */
object ActivityApi {
    private const val BASE_URL = "http://127.0.0.1:5600/api/0/"
    private const val TIMEOUT = 60L

    private lateinit var api: ActivityService

    fun init() {
        if (::api.isInitialized) return
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
            .build()
        val gson = GsonBuilder().setLenient().create()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        api = retrofit.create(ActivityService::class.java)
    }

    val service: ActivityService get() = api
}

data class BucketInfo(
    val id: String = "",
    val type: String? = null,
    val client: String? = null,
    val hostname: String? = null,
    val created: String? = null,
    val last_updated: String? = null,
)

data class EventDto(
    val timestamp: String = "",
    val duration: Double = 0.0,
    val data: JsonObject? = null,
)

interface ActivityService {
    @GET("buckets")
    suspend fun getBuckets(): Map<String, BucketInfo>

    @GET("buckets/{bucket_id}/events")
    suspend fun getEvents(
        @Path("bucket_id") bucketId: String,
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("limit") limit: Long? = null,
    ): List<EventDto>
}
