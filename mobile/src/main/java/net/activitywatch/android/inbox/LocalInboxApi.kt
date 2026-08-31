package net.activitywatch.android.inbox

import android.content.Context
import com.google.gson.GsonBuilder
import net.activitywatch.android.db.DeviceIdProvider
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

object LocalInboxApi {
    private const val BASE_URL = "http://127.0.0.1:5600/"
    private const val TIMEOUT = 30L

    private lateinit var api: InboxService

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
        val gson = GsonBuilder().setLenient().create()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        api = retrofit.create(InboxService::class.java)
    }

    val service: InboxService get() = api
}

interface InboxService {
    @GET("inbox/notes")
    suspend fun getNotes(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("tag") tag: String? = null,
        @Query("search") search: String? = null,
        @Query("deleted") deleted: Boolean? = null,
    ): List<NoteResponse>

    @POST("inbox/notes")
    suspend fun createNote(@Body payload: UpsertNotePayload): NoteResponse

    @PUT("inbox/notes/{id}")
    suspend fun updateNote(@Path("id") id: Long, @Body payload: UpsertNotePayload): NoteResponse

    @DELETE("inbox/notes/{id}")
    suspend fun deleteNote(@Path("id") id: Long): Response<Void>

    @PUT("inbox/notes/{id}/restore")
    suspend fun restoreNote(@Path("id") id: Long): NoteResponse

    @GET("inbox/notes/{id}")
    suspend fun getNote(@Path("id") noteId: Long): NoteResponse

    @GET("inbox/notes/{id}/history")
    suspend fun getNoteHistory(@Path("id") noteId: Long): List<NoteHistoryItem>

    @GET("inbox/notes/{id}/relations")
    suspend fun getNoteRelations(@Path("id") noteId: Long): List<NoteRelationResponse>

    @GET("inbox/notes/{id}/comments")
    suspend fun getComments(@Path("id") noteId: Long): List<NoteResponse>

    @POST("inbox/notes/{id}/comments")
    suspend fun addComment(@Path("id") noteId: Long, @Body payload: CreateCommentPayload): NoteResponse

    @GET("inbox/tags")
    suspend fun getTags(): List<String>

    @GET("inbox/tags/detailed")
    suspend fun getDetailedTags(): List<DetailedTag>
}