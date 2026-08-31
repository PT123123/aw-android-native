package net.activitywatch.android.sync

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object SyncApiClient {
    // 与 MainActivity.baseURL 一致：应用内 Rust server 固定监听本机 5600
    private const val BASE_URL = "http://127.0.0.1:5600/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: SyncApi = retrofit.create(SyncApi::class.java)
}
