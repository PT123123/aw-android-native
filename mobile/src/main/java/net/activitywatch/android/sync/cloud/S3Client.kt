package net.activitywatch.android.sync.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 兼容存储客户端（实验性），手写 AWS SigV4 签名。
 * 默认 path-style（endpoint/bucket/key），兼容 MinIO / Cloudflare R2 等；
 * 关闭 path-style 则用 virtual-host 风格（bucket.endpoint/key）。
 *
 * 仅用到 ListObjectsV2（test）、PutObject（upload）、GetObject（download）。
 */
class S3Client(
    endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val prefix: String,
    private val pathStyle: Boolean
) : CloudClient {

    private val base: HttpUrl = endpoint.trim().toHttpUrlOrNull()
        ?: throw CloudSyncException("Endpoint 无效，请以 http(s):// 开头")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun objectKey(fileName: String): String {
        val p = prefix.trim('/')
        return if (p.isEmpty()) fileName else "$p/$fileName"
    }

    /** 是否显式指定了非默认端口 */
    private val explicitPort: Int? = run {
        val default = if (base.isHttps) 443 else 80
        if (base.port == default) null else base.port
    }

    /** 对象的请求 URL（host/path 由 path-style 决定），OkHttp 负责路径段编码 */
    private fun objectUrl(key: String): HttpUrl {
        val b = base.newBuilder()
        if (pathStyle) {
            b.addPathSegment(bucket)
        } else {
            b.host("$bucket.${base.host}")
            explicitPort?.let { b.port(it) }
        }
        for (seg in key.split('/')) {
            if (seg.isNotBlank()) b.addPathSegment(seg)
        }
        return b.build()
    }

    // ── SigV4 ─────────────────────────────────────────────

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))

    private fun amzDate(): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val stamp = f.format(Date())
        return stamp to stamp.substring(0, 8)
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun hostHeader(url: HttpUrl): String =
        if (explicitPort == null) url.host else "${url.host}:${url.port}"

    private fun sign(url: HttpUrl, method: String, payloadHash: String, query: String?): Request {
        val (stamp, dateStamp) = amzDate()
        val canonicalUri = url.encodedPath.let { if (it.endsWith("/") && it.length > 1) it.trimEnd('/') else it }
        val canonicalQuery = query ?: ""
        val canonicalHeaders =
            "host:${hostHeader(url)}\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$stamp\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest =
            "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign =
            "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, "s3")
        val kSigning = hmac(kService, "aws4_request")
        val signature = hmac(kSigning, stringToSign).joinToString("") { "%02x".format(it) }

        return Request.Builder()
            .url(url)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", stamp)
            .header(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
            )
            .method(method, null)
            .build()
    }

    // ── CloudClient ───────────────────────────────────────

    override suspend fun test(): String = withContext(Dispatchers.IO) {
        val p = prefix.trim('/')
        val query = "list-type=2&max-keys=1" + if (p.isEmpty()) "" else "&prefix=${enc(p)}/"
        val listUrl = objectUrl("").newBuilder()?.query(query)?.build()
            ?: throw CloudSyncException("构造请求失败")
        val req = sign(listUrl, "GET", sha256Hex(ByteArray(0)), query)
        http.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> "连接成功：bucket=$bucket" + if (p.isNotEmpty()) "，前缀=$p/" else ""
                resp.code == 403 -> throw CloudSyncException("签名/权限被拒（403），请检查 Access Key 与 Secret Key")
                resp.code == 404 -> throw CloudSyncException("Bucket 不存在（404）")
                else -> throw CloudSyncException("服务器返回 ${resp.code}，无法访问 bucket")
            }
        }
    }

    override suspend fun upload(fileName: String, data: String): Unit = withContext(Dispatchers.IO) {
        val body = data.toByteArray(Charsets.UTF_8)
        val req = sign(objectUrl(objectKey(fileName)), "PUT", sha256Hex(body), null)
            .newBuilder()
            .put(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw CloudSyncException("上传失败（HTTP ${resp.code}）")
            }
        }
    }

    override suspend fun download(fileName: String): String = withContext(Dispatchers.IO) {
        val req = sign(objectUrl(objectKey(fileName)), "GET", sha256Hex(ByteArray(0)), null)
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw CloudSyncException("云端不存在该备份文件")
                !resp.isSuccessful -> throw CloudSyncException("下载失败（HTTP ${resp.code}）")
                else -> resp.body?.string() ?: throw CloudSyncException("下载失败：响应为空")
            }
        }
    }
}
