package net.activitywatch.android.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

/** POST /api/0/query 的请求体：一段 query2 脚本 + 时间区间列表。 */
data class QueryRequest(
    val timeperiods: List<String>,
    val query: List<String>,
)

/** 创建 bucket 的请求体。hostname 传 "!local" 时服务端会填本机名。 */
data class CreateBucketRequest(
    val id: String,
    val type: String,
    val client: String,
    val hostname: String = "!local",
)

interface ActivityService {
    @GET("buckets")
    suspend fun getBuckets(): Map<String, BucketInfo>

    @GET("buckets/{bucket_id}")
    suspend fun getBucket(@Path("bucket_id") bucketId: String): BucketInfo

    @POST("buckets/{bucket_id}")
    suspend fun createBucket(
        @Path("bucket_id") bucketId: String,
        @Body bucket: CreateBucketRequest,
    ): Response<Unit>

    @GET("buckets/{bucket_id}/events")
    suspend fun getEvents(
        @Path("bucket_id") bucketId: String,
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("limit") limit: Long? = null,
    ): List<EventDto>

    @POST("buckets/{bucket_id}/events")
    suspend fun createEvents(
        @Path("bucket_id") bucketId: String,
        @Body events: List<EventDto>,
    ): Response<Unit>

    /**
     * 执行 query2 脚本。返回类型用 JsonElement 收，结构随脚本而变，
     * 由调用方自己 pretty-print 展示。
     */
    @POST("query/")
    suspend fun query(@Body req: QueryRequest): JsonElement
}
