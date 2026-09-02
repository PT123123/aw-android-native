package net.activitywatch.android.todo

import android.content.Context
import com.google.gson.GsonBuilder
import net.activitywatch.android.db.DeviceIdProvider
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * 访问本机内置 aw-server-rust（127.0.0.1:5600）的 Todo REST API。
 * 与 LocalInboxApi 同构：同 baseUrl、同 X-Device-ID 设备头，路径前缀 /inbox/todos。
 * 服务端实现见 aw-server-rust/aw-inbox-rust（feature/inbox 分支）。
 */
object TodoApi {
    private const val BASE_URL = "http://127.0.0.1:5600/"
    private const val TIMEOUT = 30L

    private lateinit var api: TodoService

    fun init(context: Context) {
        if (::api.isInitialized) return
        val deviceId = DeviceIdProvider.getDeviceId(context.applicationContext)
        val deviceInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Device-ID", deviceId)
                .build()
            chain.proceed(request)
        }
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(deviceInterceptor)
            .addInterceptor(logging)
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
            .build()
        // 注意：Gson 默认不序列化 null 字段，UpdateTodoPayload 里未修改的字段会自动省略
        val gson = GsonBuilder().setLenient().create()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        api = retrofit.create(TodoService::class.java)
    }

    val service: TodoService get() = api
}

interface TodoService {
    @GET("inbox/todos")
    suspend fun getTodos(
        @Query("completed") completed: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): List<TodoResponse>

    @GET("inbox/todos/{id}")
    suspend fun getTodo(@Path("id") id: Long): TodoResponse

    @POST("inbox/todos")
    suspend fun createTodo(@Body payload: CreateTodoPayload): TodoResponse

    @PUT("inbox/todos/{id}")
    suspend fun updateTodo(@Path("id") id: Long, @Body payload: UpdateTodoPayload): TodoResponse

    @DELETE("inbox/todos/{id}")
    suspend fun deleteTodo(@Path("id") id: Long): Response<Void>
}
