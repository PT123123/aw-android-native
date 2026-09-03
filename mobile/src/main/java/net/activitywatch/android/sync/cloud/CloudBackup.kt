package net.activitywatch.android.sync.cloud

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.activitywatch.android.inbox.LocalInboxApi
import net.activitywatch.android.inbox.NoteResponse
import net.activitywatch.android.inbox.UpsertNotePayload
import net.activitywatch.android.todo.LocalTodoStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云备份（实验性）数据层：
 * 把本机数据打包成一个 JSON 备份文件（Todo 本地文件 + Inbox 笔记——从本机内置
 * aw-server-rust 的 /inbox/notes 导出真实数据，不再依赖已废弃的 Room InboxDatabase），
 * 由 [WebDavClient] / [S3Client] 上传到云端；恢复时反向写回。
 *
 * 备份格式（schema 1）：
 * {
 *   "app": "activitywatch-android",
 *   "schema": 1,
 *   "created_at": "2026-09-03T06:30:00",
 *   "device": "...",
 *   "todo": { "file": "todo_local.json", "contents": "..." | null },
 *   "inbox": { "notes": [ { NoteResponse... } ] | null }
 * }
 */
object CloudBackup {

    const val DEFAULT_FILE_NAME = "aw_backup.json"

    private val gson = Gson()

    data class RestoreResult(
        val todoRestored: Boolean,
        val notesRestored: Int
    )

    /** 构建备份 JSON（挂起函数，内部切 IO 线程） */
    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val todoFile = File(context.filesDir, LocalTodoStore.FILE_NAME)
        val todoContents = if (todoFile.exists()) {
            try {
                todoFile.readText().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        } else null

        // Inbox 笔记：从本机 aw-server-rust 的 /inbox/notes 导出（真实数据；服务端默认不含软删）
        val notes: List<NoteResponse> = try {
            LocalInboxApi.init(context)
            LocalInboxApi.service.getNotes(limit = 100_000)
        } catch (_: Exception) {
            emptyList()
        }

        val root = linkedMapOf<String, Any?>(
            "app" to "activitywatch-android",
            "schema" to 1,
            "created_at" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            "todo" to linkedMapOf(
                "file" to LocalTodoStore.FILE_NAME,
                "contents" to todoContents
            ),
            "inbox" to linkedMapOf(
                "notes" to notes
            )
        )
        gson.toJson(root)
    }

    /**
     * 从备份 JSON 恢复到本机：
     * - Todo：覆写 todo_local.json（内存中的 LocalTodoStore 不自动重载，重启应用后生效）
     * - Inbox：经 /inbox/notes 逐条重建笔记（保留内容/标签/创建时间；不保留原 id/uuid，视为新建）
     */
    suspend fun restore(context: Context, json: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(json).asJsonObject
        var todoRestored = false
        var notesRestored = 0

        val todo = root.getAsJsonObject("todo")
        if (todo != null && todo.has("contents") && !todo.get("contents").isJsonNull) {
            val contents = todo.get("contents").asString
            if (contents.isNotBlank()) {
                val file = File(context.filesDir, LocalTodoStore.FILE_NAME)
                val tmp = File(context.filesDir, LocalTodoStore.FILE_NAME + ".restore.tmp")
                tmp.writeText(contents)
                if (!tmp.renameTo(file)) {
                    file.writeText(contents)
                    tmp.delete()
                }
                todoRestored = true
            }
        }

        val inbox = root.getAsJsonObject("inbox")
        if (inbox != null && inbox.has("notes") && !inbox.get("notes").isJsonNull) {
            val notesArr = inbox.getAsJsonArray("notes")
            val type = TypeToken.getParameterized(List::class.java, NoteResponse::class.java).type
            val notes: List<NoteResponse> = gson.fromJson(notesArr, type)
            if (notes.isNotEmpty()) {
                LocalInboxApi.init(context)
                for (n in notes) {
                    try {
                        LocalInboxApi.service.createNote(
                            UpsertNotePayload(content = n.content, tags = n.tags, created_at = n.created_at)
                        )
                        notesRestored++
                    } catch (_: Exception) {
                        // 单条失败不中断整体恢复
                    }
                }
            }
        }

        RestoreResult(todoRestored, notesRestored)
    }
}
