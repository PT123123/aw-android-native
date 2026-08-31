package net.activitywatch.android.sync

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

class SyncUiException(message: String) : Exception(message)

// 薄封装：把网络异常转成可直接展示的人话（复刻原 SyncApiException 语义）
class SyncRepository(val api: SyncApi = SyncApiClient.api) {

    suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: SyncUiException) {
        Result.failure(e)
    } catch (e: HttpException) {
        Result.failure(SyncUiException(httpErrorMessage(e)))
    } catch (e: SocketTimeoutException) {
        Result.failure(SyncUiException("连接超时，同步服务未响应"))
    } catch (e: IOException) {
        Result.failure(SyncUiException("无法连接同步服务，请确认应用内服务已启动"))
    } catch (e: Exception) {
        Result.failure(SyncUiException(e.message ?: e.toString()))
    }

    private fun httpErrorMessage(e: HttpException): String {
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        if (!body.isNullOrBlank()) {
            try {
                val obj = JsonParser.parseString(body).asJsonObject
                val msg = obj.get("message") ?: obj.get("error")
                if (msg != null && !msg.isJsonNull) return msg.asString
            } catch (_: Exception) {
                // 非 JSON 错误体，落到状态码提示
            }
        }
        return "服务器错误 (${e.code()})"
    }
}
