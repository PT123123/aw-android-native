package net.activitywatch.android.sync.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * WebDAV 客户端（实验性）。
 * - test(): PROPFIND Depth:0 探测远程目录（404 视为"目录不存在，备份时自动创建"）
 * - upload(): PUT 上传；若父目录不存在（409）逐级 MKCOL 后重试一次
 * - download(): GET 拉取
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val remoteDir: String
) : CloudClient {

    private val base: HttpUrl = baseUrl.trim().toHttpUrlOrNull()
        ?: throw CloudSyncException("服务器地址无效，请以 http(s):// 开头")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 远程文件完整 URL：base + dir + fileName（逐段 URL 编码） */
    private fun fileUrl(fileName: String): HttpUrl {
        val b = base.newBuilder()
        for (seg in remoteDir.split('/').filter { it.isNotBlank() }) b.addPathSegment(seg)
        b.addPathSegment(fileName)
        return b.build()
    }

    private fun dirUrl(): HttpUrl {
        val b = base.newBuilder()
        for (seg in remoteDir.split('/').filter { it.isNotBlank() }) b.addPathSegment(seg)
        return b.build()
    }

    private fun Request.Builder.auth(): Request.Builder {
        if (username.isNotEmpty() || password.isNotEmpty()) {
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            header("Authorization", "Basic $token")
        }
        return this
    }

    override suspend fun test(): String = withContext(Dispatchers.IO) {
        val url = dirUrl()
        val req = Request.Builder()
            .url(url)
            .header("Depth", "0")
            .method("PROPFIND", null)
            .auth()
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                207, 200 -> "连接成功：${url.encodedPath}"
                404 -> "连接成功（远程目录不存在，备份时会自动创建）"
                401, 403 -> throw CloudSyncException("认证失败（${resp.code}），请检查用户名/密码")
                else -> throw CloudSyncException("服务器返回 ${resp.code}，无法确认目录可用")
            }
        }
    }

    override suspend fun upload(fileName: String, data: String): Unit = withContext(Dispatchers.IO) {
        val url = fileUrl(fileName)
        var resp = Request.Builder()
            .url(url)
            .put(data.toByteArray(Charsets.UTF_8).toRequestBody("application/octet-stream".toMediaType()))
            .auth()
            .build()
            .let { http.newCall(it).execute() }
        if (resp.code == 409) {
            resp.close()
            mkcolParents()
            resp = Request.Builder()
                .url(url)
                .put(data.toByteArray(Charsets.UTF_8).toRequestBody("application/octet-stream".toMediaType()))
                .auth()
                .build()
                .let { http.newCall(it).execute() }
        }
        resp.use {
            if (!it.isSuccessful) {
                throw CloudSyncException("上传失败（HTTP ${it.code}）")
            }
        }
    }

    /** 逐级创建远程目录（已存在时 MKCOL 返回 405，忽略即可） */
    private fun mkcolParents() {
        val segs = remoteDir.split('/').filter { it.isNotBlank() }
        var partial = base.newBuilder()
        for (seg in segs) {
            partial = partial.addPathSegment(seg)
            val req = Request.Builder()
                .url(partial.build())
                .method("MKCOL", null)
                .auth()
                .build()
            try {
                http.newCall(req).execute().close()
            } catch (_: Exception) {
                // 单级失败不阻断（可能已存在）
            }
        }
    }

    override suspend fun download(fileName: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(fileUrl(fileName))
            .get()
            .auth()
            .build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw CloudSyncException("云端不存在该备份文件")
                !resp.isSuccessful -> throw CloudSyncException("下载失败（HTTP ${resp.code}）")
                else -> resp.body?.string() ?: throw CloudSyncException("下载失败：响应为空")
            }
        }
    }
}
