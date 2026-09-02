package net.activitywatch.android.sync.cloud

/**
 * 云存储协议客户端接口（实验性）。
 * 实现类：[WebDavClient]（WebDAV）、[S3Client]（S3 兼容存储）。
 * 所有方法为挂起函数，失败时抛 [CloudSyncException]（message 为可直接展示的中文提示）。
 */
interface CloudClient {
    /** 测试连接 / 登录凭据，成功返回描述文本 */
    suspend fun test(): String

    /** 上传备份文件（覆盖同名对象） */
    suspend fun upload(fileName: String, data: String)

    /** 下载备份文件内容；云端不存在时抛异常 */
    suspend fun download(fileName: String): String
}

/** 云同步操作失败（带用户可读的中文信息） */
class CloudSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
